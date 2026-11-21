package com.labos.kernel.workflow.runtime.device.contract;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Transport security mode for Direction A Sidecar connections. */
public enum SecurityMode {
    /** Strictly restricted to local loopback addresses ({@code 127.0.0.1}, {@code ::1}, {@code localhost}). */
    DEV_LOOPBACK_PLAINTEXT,
    /** Mandatory production/LAN mode enforcing TLS 1.3 mutual authentication and SAN identity binding. */
    TLS13_MTLS;

    /** Validates fail-closed that {@link #DEV_LOOPBACK_PLAINTEXT} never binds or dials non-loopback hosts. */
    public void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Endpoint host is required");
        }
        if (this == DEV_LOOPBACK_PLAINTEXT && !isLoopbackHost(host)) {
            throw new IllegalArgumentException(
                    "DEV_LOOPBACK_PLAINTEXT is forbidden for non-loopback host: " + host
                            + " (use TLS13_MTLS for non-loopback workstation endpoints)");
        }
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("localhost".equalsIgnoreCase(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(normalized);
            return addr.isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
