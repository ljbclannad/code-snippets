package com.lx.governance.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Jsons {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsons() {}

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
