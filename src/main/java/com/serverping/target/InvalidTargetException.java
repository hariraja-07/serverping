package com.serverping.target;

/**
 * The host points at a private or reserved address — surfaces as HTTP 422.
 *
 * <p>This is the SSRF guard. Without it, anyone able to reach the API could make your server
 * connect to {@code 169.254.169.254} (cloud metadata / credential theft), scan your internal
 * network, or use you as a proxy to hammer a third party.
 */
public class InvalidTargetException extends RuntimeException {

    private final String host;
    private final String resolvedAddress;

    public InvalidTargetException(String host, String resolvedAddress) {
        super("Host '" + host + "' resolves to a private or reserved address: " + resolvedAddress);
        this.host = host;
        this.resolvedAddress = resolvedAddress;
    }

    public String getHost() {
        return host;
    }

    public String getResolvedAddress() {
        return resolvedAddress;
    }
}
