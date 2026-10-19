package com.serverping.target;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body accepted by {@code POST} and {@code PUT /api/v1/targets}.
 *
 * <p>{@code name}, {@code host} and {@code port} are required. The rest are optional so a
 * minimal request works: an absent {@code protocol} becomes {@link Protocol#TCP}, an absent
 * {@code intervalSeconds} becomes {@code 60}, an absent {@code enabled} becomes {@code true}.
 * Numeric constraints are ignored when the value is {@code null}, which is what makes those
 * three defaultable while still rejecting out-of-range input like {@code 5}.
 *
 * <p>This record only checks <em>shape</em>. Whether {@code host} points at a private or
 * reserved address is decided by {@link TargetValidator}, because that requires DNS.
 */
public record TargetRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @NotBlank(message = "host must not be blank")
        @Size(max = 255, message = "host must be at most 255 characters")
        String host,

        @NotNull(message = "port is required")
        @Min(value = 1, message = "port must be between 1 and 65535")
        @Max(value = 65535, message = "port must be between 1 and 65535")
        Integer port,

        /** Defaults to {@link Protocol#TCP} when omitted. */
        Protocol protocol,

        @Min(value = 10, message = "intervalSeconds must be at least 10")
        @Max(value = 86400, message = "intervalSeconds must be at most 86400")
        Integer intervalSeconds,

        /** Defaults to {@code true} when omitted. */
        Boolean enabled) {
}
