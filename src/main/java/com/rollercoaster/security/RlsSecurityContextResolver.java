package com.rollercoaster.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class RlsSecurityContextResolver {

    public SecurityPredicateContext resolveUserPredicates(UUID modelId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return SecurityPredicateContext.anonymous("default-tenant");
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(ga -> ga.getAuthority())
                .collect(Collectors.toSet());

        String username = authentication.getName();
        String tenantId = "default-tenant";
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);

        if (authentication.getPrincipal() instanceof Map<?, ?> details) {
            for (Map.Entry<?, ?> entry : details.entrySet()) {
                if (entry.getKey() != null) {
                    claims.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            if (claims.containsKey("tenantId")) {
                tenantId = String.valueOf(claims.get("tenantId"));
            }
        }

        String rlsHash = hashString(username + ":" + tenantId + ":" + authorities);

        return new SecurityPredicateContext(
                username,
                username + "@rollercoaster.com",
                username,
                tenantId,
                rlsHash,
                false,
                claims,
                authorities,
                Map.of());
    }

    public String injectPredicates(String baseSqlQuery, SecurityPredicateContext context) {
        if (context == null || baseSqlQuery == null) {
            return baseSqlQuery;
        }
        return baseSqlQuery;
    }

    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
