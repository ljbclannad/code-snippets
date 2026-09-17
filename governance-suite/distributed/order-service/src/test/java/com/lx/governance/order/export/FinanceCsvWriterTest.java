package com.lx.governance.order.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class FinanceCsvWriterTest {
    @Test
    void writesRfc4180Utf8CompatibleRowsAndSanitizesFormulas() throws Exception {
        StringWriter output = new StringWriter();
        new FinanceCsvWriter().write(output, List.of(new FinanceExportRow("O,1", "\t=SUM(A1:A2)", "P\"1", 2, 123L,
                "PAID\nREVIEW", null, "@danger", Instant.parse("2026-09-17T10:00:00Z"), Instant.parse("2026-09-17T09:00:00Z"))));

        assertThat(output.toString()).isEqualTo("\"orderNo\",\"customerId\",\"productId\",\"quantity\",\"amountCents\",\"status\",\"paymentStatus\",\"payNo\",\"paymentUpdatedAt\",\"createdAt\"\r\n"
                + "\"O,1\",\"'\t=SUM(A1:A2)\",\"P\"\"1\",\"2\",\"123\",\"PAID\nREVIEW\",\"\",\"'@danger\",\"2026-09-17T10:00:00Z\",\"2026-09-17T09:00:00Z\"\r\n");
    }

    @Test
    void writesEmptyValuesForNullCells() throws Exception {
        StringWriter output = new StringWriter();
        new FinanceCsvWriter().write(output, List.of(new FinanceExportRow(null, null, null, null, null, null, null, null, null, null)));
        assertThat(output.toString()).contains("\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n");
    }
}
