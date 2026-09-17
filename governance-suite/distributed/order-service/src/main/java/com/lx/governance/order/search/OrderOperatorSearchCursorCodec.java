package com.lx.governance.order.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderOperatorSearchCursorCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final String secret;

    public OrderOperatorSearchCursorCodec(
            @Value("${app.admin-search.cursor-secret:governance-suite-admin-search-secret}") String secret) {
        this.secret = secret;
    }

    public String encode(OrderOperatorSearchCursor cursor, OrderOperatorSearchCriteria criteria) {
        try {
            String payload = "v3|" + cursor.sortValue() + "|" + cursor.orderNo() + "|" + criteria.fingerprint();
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return encoded + "." + sign(encoded);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to encode cursor", exception);
        }
    }

    public OrderOperatorSearchCursor decode(String value, OrderOperatorSearchCriteria criteria) {
        try {
            String[] parts = value.split("\\.");
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) throw invalidCursor();
            String[] payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (payload.length != 4 || !"v3".equals(payload[0]) || !payload[3].equals(criteria.fingerprint())
                    || payload[1].isBlank() || payload[2].isBlank()) throw invalidCursor();
            return new OrderOperatorSearchCursor(payload[1], payload[2]);
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("invalid cursor");
    }
}
