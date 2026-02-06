package com.example.dify;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class DifyHttp {
    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;

    public DifyHttp(String baseUrl, String apiKey) {
        this.client = HttpClient.newHttpClient();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
    }

    public String post(String path, String jsonBody) throws IOException, InterruptedException {
        return post(path, jsonBody, "");
    }

    public String post(String path, String jsonBody, String traceId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
        if (traceId != null && !traceId.trim().isEmpty()) {
            builder.header("X-Trace-Id", traceId.trim());
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + response.body());
        }
        return response.body();
    }

    public String get(String path) throws IOException, InterruptedException {
        return get(path, "");
    }

    public String get(String path, String traceId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey);
        if (traceId != null && !traceId.trim().isEmpty()) {
            builder.header("X-Trace-Id", traceId.trim());
        }

        HttpRequest request = builder.GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + response.body());
        }
        return response.body();
    }

    private static String normalizeBaseUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
