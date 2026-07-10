package com.spring.simulation.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class YamlPropertySource implements PropertySource {

    private final Map<String, String> yamlEnvMap = new ConcurrentHashMap<>();

    public YamlPropertySource() {
    }

    public YamlPropertySource(Map<String, String> properties) {
        if (properties != null) {
            this.yamlEnvMap.putAll(properties);
        }
    }

    public YamlPropertySource(String filePath) throws IOException {
        this(Paths.get(filePath));
    }

    public YamlPropertySource(Path yamlPath) throws IOException {
        loadFromFile(yamlPath);
    }

    @Override
    public String getPropertySource(String name) {
        return yamlEnvMap.get(name);
    }

    @Override
    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(yamlEnvMap);
    }

    @Override
    public int getOrder() {
        return 50;
    }

    private void loadFromFile(Path yamlPath) throws IOException {
        if (yamlPath == null || !Files.exists(yamlPath)) {
            return;
        }
        parseYamlLines(Files.readAllLines(yamlPath));
    }

    /**
     * 最小 YAML 解析：
     * 1. 支持通过缩进表示层级
     * 2. 将层级拍平为 a.b.c=value
     * 3. 跳过空行和注释行
     *
     * 示例：
     * server:
     *   port: 8080
     * 会被解析为：
     * server.port=8080
     */
    private void parseYamlLines(List<String> lines) {
        Deque<String> keyStack = new ArrayDeque<>();
        Deque<Integer> indentStack = new ArrayDeque<>();

        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }

            String trimmedLine = rawLine.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }

            int indent = countIndent(rawLine);
            while (!indentStack.isEmpty() && indent <= indentStack.peek()) {
                indentStack.pop();
                keyStack.pop();
            }

            int colonIndex = trimmedLine.indexOf(':');
            if (colonIndex < 0) {
                continue;
            }

            String key = trimmedLine.substring(0, colonIndex).trim();
            String value = trimmedLine.substring(colonIndex + 1).trim();

            if (value.isEmpty()) {
                keyStack.push(key);
                indentStack.push(indent);
                continue;
            }

            String fullKey = buildFullKey(keyStack, key);
            yamlEnvMap.put(fullKey, normalizeValue(value));
        }
    }

    private int countIndent(String line) {
        int indent = 0;
        while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
            indent++;
        }
        return indent;
    }

    private String buildFullKey(Deque<String> keyStack, String currentKey) {
        if (keyStack.isEmpty()) {
            return currentKey;
        }

        StringBuilder builder = new StringBuilder();
        Object[] keys = keyStack.toArray();
        for (int i = keys.length - 1; i >= 0; i--) {
            builder.append(keys[i]).append('.');
        }
        builder.append(currentKey);
        return builder.toString();
    }

    private String normalizeValue(String value) {
        String normalized = value;
        int commentIndex = normalized.indexOf(" #");
        if (commentIndex >= 0) {
            normalized = normalized.substring(0, commentIndex).trim();
        }

        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }
}
