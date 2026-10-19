package com.serverping.target;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/targets/{id}/check} over HTTP: 404 for an unknown target, a bounded
 * synchronous response, and — importantly — that the probe's result is written back onto the
 * target so a later {@code GET} reflects it.
 *
 * <p>Uses an intentionally unresolvable host so the whole register → check → read-back path is
 * exercised without depending on any external service being reachable. The genuine UP path is
 * covered by {@link CheckServiceTests} against a real local listener.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TargetRepository targetRepository;

    @BeforeEach
    void cleanSlate() {
        targetRepository.deleteAll();
    }

    @Test
    void checkingUnknownTargetIsProblemJson404() throws Exception {
        mockMvc.perform(post("/api/v1/targets/999999/check"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Target Not Found"))
                .andExpect(jsonPath("$.detail").value("No target with id 999999"));
    }

    @Test
    void checkRunsSynchronouslyAndReturnsTheProbeResult() throws Exception {
        long id = register("Ghost", "no-such-host.invalid", 80);

        mockMvc.perform(post("/api/v1/targets/{id}/check", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(id))
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.detail").value("DNS_FAILURE"))
                .andExpect(jsonPath("$.checkedAt").exists());
    }

    @Test
    void checkResultIsWrittenBackOntoTheTarget() throws Exception {
        long id = register("Ghost", "no-such-host.invalid", 80);

        mockMvc.perform(post("/api/v1/targets/{id}/check", id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/targets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.consecutiveFails").value(1))
                .andExpect(jsonPath("$.lastCheckedAt").exists());
    }

    @Test
    void repeatedFailuresAccumulateTheConsecutiveCounter() throws Exception {
        long id = register("Ghost", "no-such-host.invalid", 80);

        mockMvc.perform(post("/api/v1/targets/{id}/check", id)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/targets/{id}/check", id)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/targets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consecutiveFails").value(2));
    }

    private long register(String name, String host, int port) throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","host":"%s","port":%d}
                                """.formatted(name, host, port)))
                .andExpect(status().isCreated());

        return targetRepository.findAll().get(0).getId();
    }
}
