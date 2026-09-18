package com.lx.governance.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import static com.lx.governance.evaluation.EvaluationModels.*;

/** 单实例文件仓储；版本只允许创建，运行报告不可覆盖。 */
@Component
public class EvaluationStore {
    private final Path root;
    private final ObjectMapper mapper;
    private final EvaluationEngine engine;

    public EvaluationStore(@Value("${evaluation.storage-directory:./data/evaluation}") String directory,
                           ObjectMapper mapper, EvaluationEngine engine) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.engine = engine;
    }

    public synchronized Dataset createDataset(Dataset dataset) throws IOException {
        engine.validateDataset(dataset);
        writeNew(datasetPath(dataset.id(), dataset.version()), dataset);
        return dataset;
    }

    public synchronized Dataset dataset(String id, String version) throws IOException {
        return read(datasetPath(id, version), Dataset.class);
    }

    public synchronized List<Dataset> datasets() throws IOException {
        return list(root.resolve("datasets"), Dataset.class);
    }

    public synchronized RunReport createRun(RunRequest request) throws IOException {
        if (request == null) { throw new IllegalArgumentException("运行请求不能为空"); }
        RunReport report = engine.evaluate(dataset(request.datasetId(), request.datasetVersion()), request);
        writeNew(reportPath(report.id()), report);
        return report;
    }

    public synchronized RunReport report(String id) throws IOException {
        return read(reportPath(id), RunReport.class);
    }

    /** 批次先保存最终报告快照，再发布到运行目录；重试可恢复中断的发布。 */
    public synchronized RunReport saveReport(RunReport report) throws IOException {
        Path path = reportPath(report.id());
        if (Files.exists(path)) {
            if (!report.equals(read(path, RunReport.class))) {
                throw new ConflictException("运行报告 ID 已被不同内容使用");
            }
        } else {
            writeNew(path, report);
        }
        return report;
    }

    public synchronized List<RunReport> reports() throws IOException {
        return list(root.resolve("runs"), RunReport.class).stream()
                .sorted(Comparator.comparing(RunReport::createdAt).reversed()).toList();
    }

    private Path datasetPath(String id, String version) {
        EvaluationEngine.identifier(id);
        EvaluationEngine.identifier(version);
        // 分目录避免 id/version 拼接导致同名碰撞。
        return root.resolve("datasets").resolve(id).resolve(version + ".json");
    }

    private Path reportPath(String id) {
        EvaluationEngine.identifier(id);
        return root.resolve("runs").resolve(id + ".json");
    }

    private <T> T read(Path path, Class<T> type) throws IOException {
        if (!Files.exists(path)) { throw new NoSuchElementException("指定样本集或运行记录不存在"); }
        return mapper.readValue(path.toFile(), type);
    }

    private <T> List<T> list(Path directory, Class<T> type) throws IOException {
        if (!Files.exists(directory)) { return List.of(); }
        try (var paths = Files.walk(directory, 2)) {
            var files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            var values = new ArrayList<T>();
            for (Path path : files) { values.add(read(path, type)); }
            return List.copyOf(values);
        }
    }

    private void writeNew(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        // 先写同目录临时文件，再用硬链接原子发布；已存在目标不会被替换。
        Path temporary = Files.createTempFile(path.getParent(), ".pending-", ".tmp");
        try {
            Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.WRITE);
            try { Files.createLink(path, temporary); }
            catch (FileAlreadyExistsException e) { throw new ConflictException("该版本已存在，请创建新版本"); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }
}
