package com.lx.governance.order.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class OrderOperatorSearchCriteriaTest {
    @Test
    void normalizesFiltersAndAcceptsBoundaryValues() {
        OrderOperatorSearchCriteria criteria = OrderOperatorSearchCriteria.create(" O-1 ", " token-1 ", " P-1 ",
                " CREATED ", " PREPARED ", Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-08-30T00:00:00Z"), 100L, 200L, 100);

        assertThat(criteria).isEqualTo(new OrderOperatorSearchCriteria("O-1", "token-1", "P-1", "CREATED", "PREPARED",
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-08-30T00:00:00Z"), 100L, 200L, 100));
    }

    @Test
    void rejectsInvalidLimitAndAmountRange() {
        assertThatThrownBy(() -> OrderOperatorSearchCriteria.create(null, null, null, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");
        assertThatThrownBy(() -> OrderOperatorSearchCriteria.create(null, null, null, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), 201L, 200L, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("minAmountCents must not exceed maxAmountCents");
    }

    @Test
    void rejectsCreatedTimeRangeLongerThanNinetyDays() {
        assertThatThrownBy(() -> OrderOperatorSearchCriteria.create(null, null, null, null, null,
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:01Z"), null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("created time range must not exceed 90 days");
    }
}
