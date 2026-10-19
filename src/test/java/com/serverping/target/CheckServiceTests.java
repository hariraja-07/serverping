package com.serverping.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The probe's four outcomes, driven against real sockets rather than mocks: an ephemeral
 * listener that answers, a port nothing is listening on, a name that does not resolve, and a
 * black-holed address that swallows the packets.
 *
 * <p>The SSRF policy is substituted with a mock on purpose. Production blocks loopback — which
 * is exactly what a local {@link ServerSocket} listener binds to — so without a stand-in the
 * "UP" case could not be exercised at all. Whether the real policy blocks the right ranges is
 * covered separately by {@code TargetValidator} behaviour asserted in {@code TargetControllerTests}.
 */
class CheckServiceTests {

    private static final int TIMEOUT_MS = 400;

    /** Default Mockito answer for a boolean is {@code false}, i.e. nothing is blocked. */
    private final TargetValidator permissivePolicy = mock(TargetValidator.class);

    private final TargetValidator refusingPolicy = mock(TargetValidator.class);

    private final String loopback = InetAddress.getLoopbackAddress().getHostAddress();

    @BeforeEach
    void armTheRefusingPolicy() {
        when(refusingPolicy.isBlocked(any(InetAddress.class))).thenReturn(true);
    }

    @Test
    void reportsUpWithMeasuredLatencyWhenTheHandshakeCompletes() throws Exception {
        try (ServerSocket listener =
                     new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {

            CheckService probe = new CheckService(TIMEOUT_MS, permissivePolicy);
            CheckResult result = probe.check(loopback, listener.getLocalPort());

            assertThat(result.status()).isEqualTo(Status.UP);
            assertThat(result.detail()).isNull();
            assertThat(result.latencyMs()).isNotNegative().isLessThan(TIMEOUT_MS);
        }
    }

    @Test
    void reportsRefusedWhenNothingIsListeningOnAnOtherwiseReachablePort() throws Exception {
        int closedPort;
        try (ServerSocket listener =
                     new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            closedPort = listener.getLocalPort();
        }
        // Port just released: the host answers with an immediate RST rather than going silent.

        CheckService probe = new CheckService(TIMEOUT_MS, permissivePolicy);
        CheckResult result = probe.check(loopback, closedPort);

        assertThat(result.status()).isEqualTo(Status.DOWN);
        assertThat(result.detail()).isEqualTo(CheckResult.REFUSED);
    }

    @Test
    void reportsDnsFailureWhenTheNameDoesNotResolve() {
        CheckService probe = new CheckService(TIMEOUT_MS, permissivePolicy);

        CheckResult result = probe.check("no-such-host.invalid", 80);

        assertThat(result.status()).isEqualTo(Status.DOWN);
        assertThat(result.detail()).isEqualTo(CheckResult.DNS_FAILURE);
    }

    @Test
    void refusesToDialAnAddressThePolicyBlocks() {
        CheckService probe = new CheckService(TIMEOUT_MS, refusingPolicy);

        // Never reached: no connect is attempted, so this is instant even though the
        // address would otherwise black-hole the packets.
        CheckResult result = probe.check("192.0.2.1", 81);

        assertThat(result.status()).isEqualTo(Status.DOWN);
        assertThat(result.detail()).isEqualTo(CheckResult.BLOCKED);
        assertThat(result.latencyMs()).isLessThan(300);
    }

    @Test
    void reportsTimeoutAfterTheFullBudgetAgainstABlackHoledAddress() {
        CheckService probe = new CheckService(TIMEOUT_MS, permissivePolicy);

        // TEST-NET-1: routed but never answered, so the connect burns the entire timeout.
        CheckResult result = probe.check("192.0.2.1", 81);

        assertThat(result.status()).isEqualTo(Status.DOWN);
        assertThat(result.detail()).isEqualTo(CheckResult.TIMEOUT);
        assertThat(result.latencyMs())
                .isGreaterThanOrEqualTo(TIMEOUT_MS - 50)
                .isLessThan(TIMEOUT_MS + 5_000);
    }
}
