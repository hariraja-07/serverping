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

import com.jayway.jsonpath.JsonPath;

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

    // --- history -------------------------------------------------------------------------

    @Test
    void historyIsEmptyBeforeAnyCheck() throws Exception {
        long id = register("Ghost", "no-such-host.invalid", 80);

        mockMvc.perform(get("/api/v1/targets/{id}/checks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void historyReturnsNewestFirstWithPersistedCheckIds() throws Exception {
        long id = register("Ghost", "no-such-host.invalid", 80);

        long first = runCheck(id);
        long second = runCheck(id);
        long third = runCheck(id);

        mockMvc.perform(get("/api/v1/targets/{id}/checks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].checkId").value(third))
                .andExpect(jsonPath("$[1].checkId").value(second))
                .andExpect(jsonPath("$[2].checkId").value(first))
                .andExpect(jsonPath("$[0].detail").value("DNS_FAILURE"));
    }

    @Test
    void historyLimitIsClampedRatherThanRejected() throws Exception {
        long id = register("Ghost", "no-such-host.invalid", 80);
        runCheck(id);
        runCheck(id);
        runCheck(id);

        mockMvc.perform(get("/api/v1/targets/{id}/checks", id).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Below the floor: clamped up to 1 rather than 400ing a valid read.
        mockMvc.perform(get("/api/v1/targets/{id}/checks", id).param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Far above the ceiling: clamped down instead of draining the table.
        mockMvc.perform(get("/api/v1/targets/{id}/checks", id).param("limit", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void historyForUnknownTargetIs404() throws Exception {
        mockMvc.perform(get("/api/v1/targets/999999/checks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Target Not Found"));
    }

    // --- summary -------------------------------------------------------------------------

    @Test
    void summaryCountsCheckedAndUncheckedTargetsSeparately() throws Exception {
        long checked = register("Checked", "no-such-host.invalid", 80);
        register("Untouched", "also-no-such-host.invalid", 80);
        runCheck(checked);

        mockMvc.perform(get("/api/v1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.up").value(0))
                .andExpect(jsonPath("$.down").value(1))
                .andExpect(jsonPath("$.unknown").value(1))
                .andExpect(jsonPath("$.avgLatencyMs").exists());
    }

    @Test
    void summaryOfAnEmptyFleetIsNullRatherThanZero() throws Exception {
        mockMvc.perform(get("/api/v1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.up").value(0))
                .andExpect(jsonPath("$.down").value(0))
                .andExpect(jsonPath("$.unknown").value(0))
                // null, not 0 — "never measured" must not read as "instant".
                .andExpect(jsonPath("$.avgLatencyMs").doesNotExist());
    }

    // --- helpers -------------------------------------------------------------------------

    private long runCheck(long id) throws Exception {
        String body = mockMvc.perform(post("/api/v1/targets/{id}/check", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Object value = JsonPath.read(body, "$.checkId");
        return ((Number) value).longValue();
    }

    private long register(String name, String host, int port) throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","host":"%s","port":%d}
                                """.formatted(name, host, port)))
                .andExpect(status().isCreated());

        return targetRepository.findAll().stream()
                .mapToLong(Target::getId)
                .max()
                .orElseThrow();
    }
}
