package com.lx.governance.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;

@Component
public class AuthTokenService {
    private static final String HMAC_ALGO = "HmacSHA256";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String tokenSecret;
    private final long tokenTtlSeconds;

    public AuthTokenService(@Value("${app.auth.token-secret:governance-suite-dev-secret}") String tokenSecret,
                            @Value("${app.auth.token-ttl-seconds:7200}") long tokenTtlSeconds) {
        this.tokenSecret = tokenSecret;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public String issueToken(long userId, String username) {
        return issueToken(userId, username, java.util.List.of());
    }

    public String issueToken(long userId, String username, Collection<String> roles) {
        try {
            AuthTokenClaims claims = new AuthTokenClaims();
            claims.setUserId(userId);
            claims.setUsername(username);
            claims.setExpireAt(Instant.now().getEpochSecond() + tokenTtlSeconds);
            claims.setRoles(AdminRole.normalize(roles));

            String payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(claims));
            String signature = sign(payload);
            return payload + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("issue token failed", e);
        }
    }

    public AuthTokenClaims verify(String rawToken) {
        try {
            String token = stripBearerPrefix(rawToken);
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid token format");
            }
            String payload = parts[0];
            String signature = parts[1];
            if (!constantTimeEquals(sign(payload), signature)) {
                throw new IllegalArgumentException("invalid token signature");
            }
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            AuthTokenClaims claims = objectMapper.readValue(decoded, AuthTokenClaims.class);
            claims.setRoles(AdminRole.normalize(claims.getRoles()));
            if (claims.getExpireAt() < Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("token expired");
            }
            return claims;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid token", e);
        }
    }

    public String stripBearerPrefix(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("missing authorization token");
        }
        if (rawToken.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return rawToken.substring(7).trim();
        }
        return rawToken.trim();
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length(); i++) {
            diff |= left.charAt(i) ^ right.charAt(i);
        }
        return diff == 0;
    }
}
