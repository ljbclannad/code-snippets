package com.lx.governance.order.export;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class FinanceCsvWriter {
    private static final List<String> HEADER = List.of("orderNo", "customerId", "productId", "quantity", "amountCents",
            "status", "paymentStatus", "payNo", "paymentUpdatedAt", "createdAt");

    public void write(Writer writer, List<FinanceExportRow> rows) throws IOException {
        writeRecord(writer, HEADER, false);
        for (FinanceExportRow row : rows) {
            writeRecord(writer, Arrays.asList(row.orderNo(), row.customerId(), row.productId(), number(row.quantity()),
                    number(row.amountCents()), row.status(), row.paymentStatus(), row.payNo(), time(row.paymentUpdatedAt()),
                    time(row.createdAt())), true);
        }
    }

    private void writeRecord(Writer writer, List<String> values, boolean sanitizeText) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(',');
            boolean textCell = index != 3 && index != 4;
            writer.write(escape(values.get(index), sanitizeText && textCell));
        }
        writer.write("\r\n");
    }

    private String escape(String value, boolean sanitizeText) {
        String safe = value == null ? "" : (sanitizeText ? formulaSafe(value) : value);
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private String formulaSafe(String value) {
        int index = 0;
        while (index < value.length() && (Character.isWhitespace(value.charAt(index)) || Character.isISOControl(value.charAt(index)))) {
            index++;
        }
        if (index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0) return "'" + value;
        return value;
    }

    private String number(Number value) {
        return value == null ? "" : value.toString();
    }

    private String time(Instant value) {
        return value == null ? "" : value.toString();
    }
}
