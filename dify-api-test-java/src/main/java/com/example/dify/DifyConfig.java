package com.example.dify;

public final class DifyConfig {
    private DifyConfig() {
    }

    public static String baseUrl() {
        String value = System.getenv("DIFY_BASE_URL");
        if (value == null || value.trim().isEmpty()) {
            return "http://localhost/v1";
        }
        return value.trim();
    }

    public static String apiKey() {
        String value = System.getenv("DIFY_API_KEY");
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("DIFY_API_KEY is required.");
        }
        return value.trim();
    }

    public static String user() {
        String value = System.getenv("DIFY_USER");
        if (value == null || value.trim().isEmpty()) {
            return "java-test-user";
        }
        return value.trim();
    }

    public static String conversationId() {
        String value = System.getenv("DIFY_CONVERSATION_ID");
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
