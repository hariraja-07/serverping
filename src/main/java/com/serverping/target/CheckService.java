package com.serverping.target;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Probes {@code host:port} with a plain TCP connect and measures the handshake.
 *
 * <p>Why connect and not {@code ping}: ICMP is a different protocol that many production hosts
 * drop entirely (AWS security groups, Cloudflare, hardened servers), so {@code ping} reports a
 * perfectly healthy service as DOWN. The JVM has no ICMP API at all — a real ping means shelling
 * out to the system binary, which needs {@code CAP_NET_RAW} and does not exist in most images.
 * TCP needs none of that and answers the question that actually matters: <em>is the service
 * accepting connections?</em>
 *
 * <p><strong>The address is pinned.</strong> The hostname is resolved once, validated against
 * the SSRF ranges, and the connection goes to that exact {@link InetAddress} — not to the name
 * again. Re-resolving at connect time would reopen the DNS-rebinding hole the validator closes:
 * a name could resolve publicly during validation and privately a millisecond later.
 *
 * <p>The timeout is what keeps a dead host from stalling the caller. Java's default connect
 * timeout is tens of seconds, which would freeze both the synchronous {@code /check} endpoint
 * and the background scheduler on a single unresponsive target.
 */
@Service
public class CheckService {

    private final int timeoutMs;
    private final TargetValidator targetValidator;

    public CheckService(@Value("${app.check-timeout-ms:2000}") int timeoutMs,
                        TargetValidator targetValidator) {
        this.timeoutMs = timeoutMs;
        this.targetValidator = targetValidator;
    }

    public CheckResult check(String host, int port) {
        long start = System.nanoTime();

        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return CheckResult.down(CheckResult.DNS_FAILURE, elapsedMs(start));
        }

        // Same predicate the registration-time guard uses, applied to the address we are
        // actually about to dial.
        if (targetValidator.isBlocked(address)) {
            return CheckResult.down(CheckResult.BLOCKED, elapsedMs(start));
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            return CheckResult.up(elapsedMs(start));
        } catch (ConnectException e) {
            return CheckResult.down(CheckResult.REFUSED, elapsedMs(start));
        } catch (SocketTimeoutException e) {
            return CheckResult.down(CheckResult.TIMEOUT, elapsedMs(start));
        } catch (UnknownHostException e) {
            return CheckResult.down(CheckResult.DNS_FAILURE, elapsedMs(start));
        } catch (Exception e) {
            return CheckResult.down(CheckResult.ERROR + "_" + e.getClass().getSimpleName(),
                    elapsedMs(start));
        }
    }

    int getTimeoutMs() {
        return timeoutMs;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
