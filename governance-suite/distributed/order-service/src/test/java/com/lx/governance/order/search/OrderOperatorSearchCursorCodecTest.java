package com.lx.governance.order.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class OrderOperatorSearchCursorCodecTest {
    private final OrderOperatorSearchCursorCodec codec = new OrderOperatorSearchCursorCodec("test-secret");

    @Test
    void restoresCursorForTheSameNormalizedCriteria() {
        OrderOperatorSearchCriteria criteria = criteria("P-1");
        String encoded = codec.encode(new OrderOperatorSearchCursor(Instant.parse("2026-09-15T10:00:00Z"), "O-2"), criteria);

        assertThat(codec.decode(encoded, criteria)).isEqualTo(new OrderOperatorSearchCursor(
                Instant.parse("2026-09-15T10:00:00Z"), "O-2"));
    }

    @Test
    void preservesSubMillisecondCursorPrecision() {
        OrderOperatorSearchCriteria criteria = criteria("P-1");
        Instant createdAt = Instant.parse("2026-09-15T10:00:00.123456Z");
        String encoded = codec.encode(new OrderOperatorSearchCursor(createdAt, "O-2"), criteria);

        assertThat(codec.decode(encoded, criteria).createdAt()).isEqualTo(createdAt);
    }

    @Test
    void rejectsMalformedCursorWithStableMessage() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor", criteria("P-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
    }

    @Test
    void rejectsCursorWhenSearchCriteriaChanges() {
        String encoded = codec.encode(new OrderOperatorSearchCursor(Instant.parse("2026-09-15T10:00:00Z"), "O-2"), criteria("P-1"));

        assertThatThrownBy(() -> codec.decode(encoded, criteria("P-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
    }

    private OrderOperatorSearchCriteria criteria(String productId) {
        return OrderOperatorSearchCriteria.create(null, null, productId, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), null, null, 20);
    }
}
