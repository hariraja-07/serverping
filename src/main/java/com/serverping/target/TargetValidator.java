package com.serverping.target;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

/**
 * SSRF guard: refuses hosts that resolve to a private or reserved address.
 *
 * <p>Runs on every write, so a bad host is rejected at <em>registration</em> time — before the
 * checker ever opens a socket. It handles both forms of input: a literal IP is checked directly,
 * and a hostname is resolved first and every resulting address is checked.
 *
 * <p><strong>Deliberately permissive on unresolvable names.</strong> A monitoring service must
 * let you watch a host that is currently down, so {@link UnknownHostException} is not an error —
 * if it cannot resolve, it cannot be pointing at a private address right now, and the checker
 * will later record {@code DNS_FAILURE} against it.
 *
 * <p><strong>Known limitation:</strong> this validates at registration. A name that resolves
 * publicly now and privately at check time (DNS rebinding) would slip through. Closing that
 * means re-validating the resolved address inside {@code CheckService} immediately before
 * connecting — worth doing when the check engine lands.
 */
@Component
public class TargetValidator {

    /** Blocks these ranges. Everything below is unreachable from, or unsafe to touch, publicly. */
    public void validate(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripBrackets(host));
        } catch (UnknownHostException e) {
            // Not a literal IP and does not currently resolve — let it through so an
            // unreachable host can still be registered and monitored.
            return;
        }

        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new InvalidTargetException(host, address.getHostAddress());
            }
        }
    }

    /**
     * Why a given address is refused.
     *
     * <p>{@code InetAddress} covers loopback, link-local (which includes the
     * {@code 169.254.169.254} cloud metadata endpoint), RFC1918 site-local and multicast for
     * free; the explicit byte checks below cover the blocks its predicates do not.
     */
    static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return true;                                    // 0.0.0.0, ::
        }
        if (address.isLoopbackAddress()) {
            return true;                                    // 127.0.0.0/8, ::1
        }
        if (address.isLinkLocalAddress()) {
            return true;                                    // 169.254.0.0/16, fe80::/10
        }
        if (address.isSiteLocalAddress()) {
            return true;                                    // 10/8, 172.16/12, 192.168/16
        }
        if (address.isMulticastAddress()) {
            return true;                                    // 224.0.0.0/4, ff00::/8
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;                                // fc00::/7  unique local
            }
            if (bytes[0] == 0xff) {
                return true;                                // ff00::/8  multicast
            }
            if (isIpv4Mapped(bytes)) {
                byte[] mapped = {bytes[12], bytes[13], bytes[14], bytes[15]};
                return isBlockedIpv4(mapped);               // ::ffff:a.b.c.d
            }
        }
        return false;
    }

    private static boolean isBlockedIpv4(byte[] b) {
        int o0 = b[0] & 0xff;
        int o1 = b[1] & 0xff;
        int o2 = b[2] & 0xff;

        if (o0 == 0) {
            return true;                                    // 0.0.0.0/8        "this network"
        }
        if (o0 == 100 && (o1 & 0xc0) == 0x40) {
            return true;                                    // 100.64.0.0/10    carrier-grade NAT
        }
        if (o0 == 192 && o1 == 0 && o2 == 0) {
            return true;                                    // 192.0.0.0/24     IETF protocol assignments
        }
        if (o0 == 198 && (o1 & 0xfe) == 18) {
            return true;                                    // 198.18.0.0/15    benchmarking
        }
        if (o0 == 198 && o1 == 51 && o2 == 100) {
            return true;                                    // 198.51.100.0/24  documentation
        }
        if (o0 == 203 && o1 == 0 && o2 == 113) {
            return true;                                    // 203.0.113.0/24   documentation
        }
        if (o0 >= 224) {
            return true;                                    // multicast, reserved, broadcast
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xff && b[11] == (byte) 0xff;
    }

    /** {@code [::1]} and {@code ::1} mean the same thing; accept both spellings. */
    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
