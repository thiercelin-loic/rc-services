package com.rollercoaster.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class JwtTokenProvider {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private static final String JWT_HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final String DEFAULT_TENANT_ID = "ws-90124-enterprise";

    public static final String CLAIM_SUBJECT = "sub";
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_FULL_NAME = "fullName";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ISSUED_AT = "iat";
    public static final String CLAIM_EXPIRATION = "exp";

    private final byte[] secretKeyBytes;
    private final long validityInMilliseconds;

    public JwtTokenProvider(
            @Value("${rollercoaster.security.jwt.secret:rollercoaster-secret-key-analytics-2026-auth-token-super-safe-32chars}") String secret,
            @Value("${rollercoaster.security.jwt.expiration-ms:86400000}") long validityInMilliseconds) {
        this.secretKeyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.validityInMilliseconds = validityInMilliseconds;
    }

    public String generateToken(UserEntity user) {
        Objects.requireNonNull(user, "User entity cannot be null when generating token");

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(validityInMilliseconds);

        String encodedHeader = base64UrlEncode(JWT_HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String payloadJson = buildPayloadJson(user, issuedAt, expiresAt);
        String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

        String contentToSign = encodedHeader + "." + encodedPayload;
        String signature = calculateHmacSha256(contentToSign);

        return contentToSign + "." + signature;
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String[] tokenParts = token.split("\\.");
        if (tokenParts.length != 3) {
            return false;
        }

        String contentToSign = tokenParts[0] + "." + tokenParts[1];
        String expectedSignature = calculateHmacSha256(contentToSign);

        byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = tokenParts[2].getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(actualBytes, expectedBytes)) {
            return false;
        }

        return !isTokenExpired(token);
    }

    public String extractUsername(String token) {
        Map<String, Object> claims = extractClaims(token);
        return (String) claims.get(CLAIM_SUBJECT);
    }

    public Map<String, Object> extractClaims(String token) {
        if (token == null || token.isBlank()) {
            return Map.of();
        }

        String[] tokenParts = token.split("\\.");
        if (tokenParts.length < 2) {
            return Map.of();
        }

        try {
            byte[] payloadBytes = base64UrlDecode(tokenParts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            return parsePayloadJson(payloadJson);
        } catch (IllegalArgumentException parseException) {
            return Map.of();
        }
    }

    private boolean isTokenExpired(String token) {
        try {
            Map<String, Object> claims = extractClaims(token);
            if (!claims.containsKey(CLAIM_EXPIRATION)) {
                return false;
            }

            long expirationEpochSeconds = Long.parseLong(String.valueOf(claims.get(CLAIM_EXPIRATION)));
            long currentEpochSeconds = Instant.now().getEpochSecond();
            return currentEpochSeconds >= expirationEpochSeconds;
        } catch (Exception exception) {
            return true;
        }
    }

    public java.time.Duration getRemainingTtl(String token) {
        try {
            Map<String, Object> claims = extractClaims(token);
            if (!claims.containsKey(CLAIM_EXPIRATION)) {
                return java.time.Duration.ofHours(24);
            }
            long expSeconds = Long.parseLong(String.valueOf(claims.get(CLAIM_EXPIRATION)));
            long diff = expSeconds - Instant.now().getEpochSecond();
            return diff > 0 ? java.time.Duration.ofSeconds(diff) : java.time.Duration.ZERO;
        } catch (Exception e) {
            return java.time.Duration.ofHours(24);
        }
    }

    private String buildPayloadJson(UserEntity user, Instant issuedAt, Instant expiresAt) {
        String userIdString = user.getId() != null ? user.getId().toString() : "";
        String fullNameString = user.getFullName() != null ? user.getFullName() : user.getUsername();
        String tenantIdString = user.getTenantId() != null ? user.getTenantId() : DEFAULT_TENANT_ID;

        return String.format(
                "{\"%s\":\"%s\",\"%s\":\"%s\",\"%s\":\"%s\",\"%s\":\"%s\",\"%s\":\"%s\",\"%s\":\"%s\",\"%s\":%d,\"%s\":%d}",
                CLAIM_SUBJECT, escapeJson(user.getUsername()),
                CLAIM_USER_ID, userIdString,
                CLAIM_EMAIL, escapeJson(user.getEmail()),
                CLAIM_FULL_NAME, escapeJson(fullNameString),
                CLAIM_ROLE, escapeJson(user.getRole()),
                CLAIM_TENANT_ID, escapeJson(tenantIdString),
                CLAIM_ISSUED_AT, issuedAt.getEpochSecond(),
                CLAIM_EXPIRATION, expiresAt.getEpochSecond()
        );
    }

    private String calculateHmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKeyBytes, HMAC_SHA256_ALGORITHM);
            mac.init(secretKeySpec);
            byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return base64UrlEncode(signatureBytes);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", exception);
        }
    }

    private String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private byte[] base64UrlDecode(String data) {
        return Base64.getUrlDecoder().decode(data);
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, Object> parsePayloadJson(String jsonString) {
        Map<String, Object> claimsMap = new HashMap<>();
        String trimmedJson = jsonString.trim();

        if (trimmedJson.startsWith("{") && trimmedJson.endsWith("}")) {
            trimmedJson = trimmedJson.substring(1, trimmedJson.length() - 1);
        }

        String[] keyValues = trimmedJson.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : keyValues) {
            parseKeyValuePair(pair, claimsMap);
        }

        return claimsMap;
    }

    private void parseKeyValuePair(String pair, Map<String, Object> claimsMap) {
        String[] keyAndValue = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 2);
        if (keyAndValue.length != 2) {
            return;
        }

        String key = keyAndValue[0].trim().replace("\"", "");
        String rawValue = keyAndValue[1].trim();

        if (rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
            claimsMap.put(key, rawValue.substring(1, rawValue.length() - 1));
        } else if ("true".equalsIgnoreCase(rawValue) || "false".equalsIgnoreCase(rawValue)) {
            claimsMap.put(key, Boolean.parseBoolean(rawValue));
        } else {
            try {
                claimsMap.put(key, Long.parseLong(rawValue));
            } catch (NumberFormatException exception) {
                claimsMap.put(key, rawValue);
            }
        }
    }
}
