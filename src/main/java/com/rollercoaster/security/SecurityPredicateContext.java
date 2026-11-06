package com.rollercoaster.security;

import java.util.Map;
import java.util.Set;

public record SecurityPredicateContext(
        String userObjectId,
        String userPrincipalName,
        String username,
        String tenantId,
        String rlsHash,
        boolean emptySelectionSet,
        Map<String, Object> userClaims,
        Set<String> authorities,
        Map<String, Object> customData
) {
    public SecurityPredicateContext {
        if (rlsHash == null) {
            rlsHash = "rls-" + (userObjectId != null ? userObjectId : "anon");
        }
        if (userClaims == null) {
            userClaims = Map.of();
        }
        if (authorities == null) {
            authorities = Set.of();
        }
        if (customData == null) {
            customData = Map.of();
        }
    }

    public static SecurityPredicateContext anonymous(String tenantId) {
        return new SecurityPredicateContext(
                "anonymous-id",
                "anonymous@rollercoaster.com",
                "anonymous",
                tenantId != null ? tenantId : "default-tenant",
                "anon-rls-hash",
                false,
                Map.of("tenantId", tenantId != null ? tenantId : "default-tenant"),
                Set.of("PERM_REPORT_READ", "PERM_MODEL_WRITE", "PERM_SYSTEM_ADMIN"),
                Map.of()
        );
    }
}
