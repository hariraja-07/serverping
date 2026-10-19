package com.serverping.target;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end HTTP behaviour of the target endpoints: status codes, the {@code Location}
 * header on create, RFC 9457 problem bodies on failure, and the SSRF guard.
 *
 * <p>Note Boot 4 relocated {@code @AutoConfigureMockMvc} to
 * {@code org.springframework.boot.webmvc.test.autoconfigure}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TargetControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TargetRepository targetRepository;

    @BeforeEach
    void cleanSlate() {
        targetRepository.deleteAll();
    }

    // --- create -------------------------------------------------------------------------

    @Test
    void createsTargetWithSensibleDefaults() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"GitHub","host":"api.github.com","port":443}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        matchesPattern("/api/v1/targets/\\d+")))
                .andExpect(jsonPath("$.name").value("GitHub"))
                .andExpect(jsonPath("$.host").value("api.github.com"))
                .andExpect(jsonPath("$.port").value(443))
                .andExpect(jsonPath("$.protocol").value("TCP"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.latencyMs").doesNotExist())
                .andExpect(jsonPath("$.consecutiveFails").value(0));
    }

    @Test
    void rejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   ","host":"api.github.com","port":443}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));
    }

    @Test
    void rejectsMissingPort() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"No port","host":"api.github.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.port").value("port is required"));
    }

    @Test
    void rejectsOutOfRangeInterval() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Too eager","host":"api.github.com","port":443,
                                 "intervalSeconds":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.intervalSeconds")
                        .value("intervalSeconds must be at least 10"));
    }

    @Test
    void rejectsUnknownProtocol() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ping","host":"api.github.com","port":443,
                                 "protocol":"ICMP"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"));
    }

    // --- SSRF guard ----------------------------------------------------------------------

    @Test
    void rejectsLoopbackLiteral() throws Exception {
        assertRejected("127.0.0.1");
    }

    @Test
    void rejectsLoopbackHostname() throws Exception {
        assertRejected("localhost");
    }

    @Test
    void rejectsPrivateNetwork() throws Exception {
        assertRejected("10.0.0.5");
        assertRejected("172.16.4.9");
        assertRejected("192.168.1.10");
    }

    @Test
    void rejectsCloudMetadataEndpoint() throws Exception {
        assertRejected("169.254.169.254");
    }

    @Test
    void rejectsCarrierGradeNatAndReservedRanges() throws Exception {
        assertRejected("100.64.0.1");
        assertRejected("198.18.0.1");
        assertRejected("0.0.0.0");
    }

    @Test
    void rejectsReservedIpv6() throws Exception {
        assertRejected("[::1]");
        assertRejected("fc00::1");
    }

    @Test
    void acceptsPubliclyRoutableAddress() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Public","host":"93.184.216.34","port":443}
                                """))
                .andExpect(status().isCreated());
    }

    // --- read ----------------------------------------------------------------------------

    @Test
    void listsTargetsInIdOrder() throws Exception {
        targetRepository.saveAll(List.of(
                new Target("One", "one.example.com", 443),
                new Target("Two", "two.example.com", 443)));

        mockMvc.perform(get("/api/v1/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("One"))
                .andExpect(jsonPath("$[1].name").value("Two"));
    }

    @Test
    void filtersByStatus() throws Exception {
        Target down = new Target("Down", "down.example.com", 80);
        down.setStatus(Status.DOWN);
        targetRepository.saveAll(List.of(
                down,
                new Target("Up", "up.example.com", 80)));

        mockMvc.perform(get("/api/v1/targets").param("status", "DOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Down"));
    }

    @Test
    void rejectsUnknownStatusFilter() throws Exception {
        mockMvc.perform(get("/api/v1/targets").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"));
    }

    @Test
    void getsSingleTarget() throws Exception {
        Target saved = targetRepository.save(new Target("Solo", "solo.example.com", 22));

        mockMvc.perform(get("/api/v1/targets/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Solo"))
                .andExpect(jsonPath("$.port").value(22));
    }

    @Test
    void missingTargetIsProblemJson404() throws Exception {
        mockMvc.perform(get("/api/v1/targets/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Target Not Found"))
                .andExpect(jsonPath("$.detail").value("No target with id 999999"));
    }

    @Test
    void unknownPathIsProblemJson404() throws Exception {
        mockMvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    // --- update and delete ---------------------------------------------------------------

    @Test
    void putReplacesEverySuppliedField() throws Exception {
        Target saved = targetRepository.save(new Target("Old", "old.example.com", 80));

        mockMvc.perform(put("/api/v1/targets/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New","host":"new.example.com","port":443,
                                 "intervalSeconds":30,"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New"))
                .andExpect(jsonPath("$.host").value("new.example.com"))
                .andExpect(jsonPath("$.port").value(443))
                .andExpect(jsonPath("$.intervalSeconds").value(30))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void deletingTwiceIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Temp","host":"temp.example.com","port":8080}
                                """))
                .andExpect(status().isCreated());

        Long id = targetRepository.findAll().get(0).getId();

        mockMvc.perform(delete("/api/v1/targets/{id}", id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(delete("/api/v1/targets/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Target Not Found"));

        mockMvc.perform(get("/api/v1/targets/{id}", id))
                .andExpect(status().isNotFound());
    }

    // --- helpers -------------------------------------------------------------------------

    /** A refused host must be rejected as 422 (understood, semantically refused) — never saved. */
    private void assertRejected(String host) throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad","host":"%s","port":443}
                                """.formatted(host)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid Target"))
                .andExpect(jsonPath("$.detail", containsString(host)));

        if (!targetRepository.findAll().isEmpty()) {
            throw new AssertionError("Target with host '" + host + "' should not have been saved");
        }
    }
}
