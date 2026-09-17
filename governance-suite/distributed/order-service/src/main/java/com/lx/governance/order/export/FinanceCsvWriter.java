package com.lx.governance.order.export;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.List;

public final class FinanceCsvWriter {
    public void write(Writer writer, List<FinanceExportRow> rows) throws IOException {
        writeRecord(writer, FinanceExportSnapshot.financeFields(), false);
        for (FinanceExportRow row : rows) {
            writeRecord(writer, values(row, FinanceExportSnapshot.financeFields()), true);
        }
    }

    public void write(Writer writer, List<FinanceExportRow> rows, String fieldSet) throws IOException {
        List<String> fields = FinanceExportSnapshot.parseFinanceFields(fieldSet);
        writeRecord(writer, fields, false);
        for (FinanceExportRow row : rows) writeRecord(writer, values(row, fields), true);
    }

    private List<String> values(FinanceExportRow row, List<String> fields) {
        return fields.stream().map(field -> switch (field) {
            case "orderNo" -> row.orderNo();
            case "customerId" -> row.customerId();
            case "productId" -> row.productId();
            case "quantity" -> number(row.quantity());
            case "amountCents" -> number(row.amountCents());
            case "status" -> row.status();
            case "paymentStatus" -> row.paymentStatus();
            case "payNo" -> row.payNo();
            case "paymentUpdatedAt" -> time(row.paymentUpdatedAt());
            case "createdAt" -> time(row.createdAt());
            default -> throw new IllegalArgumentException("unsupported frozen finance field");
        }).toList();
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
