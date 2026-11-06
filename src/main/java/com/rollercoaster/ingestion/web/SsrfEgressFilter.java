package com.rollercoaster.ingestion.web;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Egress validation filter preventing Server-Side Request Forgery (SSRF) against internal cluster
 * infrastructure across WebClient and Jsoup queries.
 * Prohibits dispatch to:
 * - Loopback addresses (127.0.0.0/8, ::1)
 * - RFC 1918 private subnets (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
 * - Link-local / cloud metadata (169.254.0.0/16, fe80::/10)
 * - RFC 4193 IPv6 Unique Local Addresses (fc00::/7)
 * - Internal Docker bridge service hostnames (mysql, redis, headless-renderer, vllm, spring-boot)
 */
@Component
public class SsrfEgressFilter {

    public void validateUri(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            throw new SecurityException("ERR_SSRF_BLOCKED: Missing URI or scheme.");
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new SecurityException("ERR_SSRF_BLOCKED: Only HTTP/HTTPS schemes are permitted: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("ERR_SSRF_BLOCKED: Missing target host.");
        }
        String lowerHost = host.toLowerCase();
        if (lowerHost.equals("localhost")
                || lowerHost.equals("mysql")
                || lowerHost.equals("redis")
                || lowerHost.equals("vllm")
                || lowerHost.equals("headless-renderer")
                || lowerHost.equals("spring-boot")
                || lowerHost.endsWith(".internal")
                || lowerHost.endsWith(".local")) {
            throw new SecurityException("ERR_SSRF_BLOCKED: Access to internal cluster host '" + host + "' is prohibited.");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                validateInetAddress(addr, host);
            }
        } catch (UnknownHostException e) {
            throw new SecurityException("ERR_SSRF_BLOCKED: Unresolvable host '" + host + "'", e);
        }
    }

    public void validateInetAddress(InetAddress addr, String originalHost) {
        if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            throw new SecurityException(
                    "ERR_SSRF_BLOCKED: Private, loopback, or link-local IP prohibited for host '"
                            + originalHost + "' (" + addr.getHostAddress() + ")");
        }

        byte[] raw = addr.getAddress();
        if (addr instanceof Inet4Address && raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 + CGNAT 100.64.0.0/10 + Link-local 169.254.0.0/16
            if (b0 == 10
                    || (b0 == 172 && b1 >= 16 && b1 <= 31)
                    || (b0 == 192 && b1 == 168)
                    || (b0 == 169 && b1 == 254)
                    || (b0 == 100 && b1 >= 64 && b1 <= 127)
                    || b0 == 127
                    || b0 == 0) {
                throw new SecurityException(
                        "ERR_SSRF_BLOCKED: RFC 1918 / Docker bridge subnet prohibited: " + addr.getHostAddress());
            }
        } else if (addr instanceof Inet6Address && raw.length == 16) {
            int firstByte = raw[0] & 0xFF;
            // RFC 4193 Unique Local Address (fc00::/7 -> 0xFC or 0xFD)
            if ((firstByte & 0xFE) == 0xFC) {
                throw new SecurityException(
                        "ERR_SSRF_BLOCKED: RFC 4193 IPv6 Unique Local Address prohibited: " + addr.getHostAddress());
            }
        }
    }
}
