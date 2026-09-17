package com.lx.governance.order.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FinanceExportFileStorage {
    private final Path root;

    public FinanceExportFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public String write(String taskId, String content) throws IOException {
        Files.createDirectories(root);
        String key = taskId + ".csv";
        Path target = resolve(key);
        Path part = resolve(taskId + ".part");
        Files.writeString(part, content);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return key;
    }

    public Path read(String key) { return resolve(key); }
    public void delete(String key) throws IOException { if (key != null) Files.deleteIfExists(resolve(key)); }
    public void deletePart(String taskId) throws IOException { Files.deleteIfExists(resolve(taskId + ".part")); }

    private Path resolve(String key) {
        if (key == null || key.contains("/") || key.contains("\\")) throw new IllegalArgumentException("invalid export file key");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("invalid export file key");
        return path;
    }
}
