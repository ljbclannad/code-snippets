package com.lx.governance.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.lx.governance.evaluation.EvaluationModels.*;

/** 单实例批次采集；完整快照原子替换，避免重启后丢失已确认的观测。 */
@Service
public class EvaluationBatchService {
    public enum BatchStatus { COLLECTING, READY, FINALIZED }
    public record CreateBatch(String datasetId, String datasetVersion, int repetitions, Experiment experiment) { }
    public record Capture(String captureId, String caseId, int trial, JsonNode input, JsonNode output,
                          Long latencyMs, Long totalTokens, String error) {
        Observation observation() { return new Observation(caseId, trial, input, output, latencyMs, totalTokens, error); }
    }
    public record Batch(String id, String createdAt, Dataset dataset, int repetitions, Experiment experiment,
                        int expectedObservations, int receivedObservations, BatchStatus status,
                        List<Capture> observations, RunReport finalizedReport) { }

    private final Path root;
    private final ObjectMapper mapper;
    private final EvaluationEngine engine;
    private final EvaluationStore store;

    public EvaluationBatchService(@Value("${evaluation.storage-directory:./data/evaluation}") String directory,
                                  ObjectMapper mapper, EvaluationEngine engine, EvaluationStore store) {
        this.root = Path.of(directory).toAbsolutePath().normalize().resolve("batches");
        this.mapper = mapper;
        this.engine = engine;
        this.store = store;
    }

    public synchronized Batch create(CreateBatch request) throws IOException {
        if (request == null || request.experiment() == null || request.experiment().source() != Source.RECORDED_REAL) {
            throw new IllegalArgumentException("采集批次必须声明 RECORDED_REAL 来源");
        }
        Dataset dataset = store.dataset(request.datasetId(), request.datasetVersion());
        engine.evaluate(dataset, new RunRequest(dataset.id(), dataset.version(), request.repetitions(),
                request.experiment(), List.of()));
        Batch batch = new Batch(UUID.randomUUID().toString(), Instant.now().toString(), dataset,
                request.repetitions(), request.experiment(), dataset.cases().size() * request.repetitions(),
                0, BatchStatus.COLLECTING, List.of(), null);
        save(batch);
        return batch;
    }

    public synchronized Batch get(String id) throws IOException {
        Path path = path(id);
        if (!Files.exists(path)) { throw new NoSuchElementException("采集批次不存在"); }
        return mapper.readValue(path.toFile(), Batch.class);
    }

    public synchronized Batch capture(String id, Capture capture) throws IOException {
        Batch batch = get(id);
        if (capture == null) { throw new IllegalArgumentException("采集观测不能为空"); }
        EvaluationEngine.identifier(capture.captureId());
        for (Capture previous : batch.observations()) {
            if (previous.captureId().equals(capture.captureId())) {
                if (previous.equals(capture)) { return batch; }
                throw new EvaluationStore.ConflictException("captureId 已用于不同的观测内容");
            }
            if (previous.caseId().equals(capture.caseId()) && previous.trial() == capture.trial()) {
                throw new EvaluationStore.ConflictException("样本的该次 trial 已被采集");
            }
        }
        if (batch.status() == BatchStatus.FINALIZED) {
            throw new EvaluationStore.ConflictException("批次已完成，不能新增观测");
        }
        List<Capture> captures = new ArrayList<>(batch.observations());
        captures.add(capture);
        // 复用引擎对样本匹配、次数、输入、耗时和 Token 的统一校验。
        engine.evaluate(batch.dataset(), request(batch, captures));
        Batch updated = new Batch(batch.id(), batch.createdAt(), batch.dataset(), batch.repetitions(),
                batch.experiment(), batch.expectedObservations(), captures.size(),
                captures.size() == batch.expectedObservations() ? BatchStatus.READY : BatchStatus.COLLECTING,
                List.copyOf(captures), null);
        save(updated);
        return updated;
    }

    public synchronized RunReport report(String id) throws IOException {
        Batch batch = get(id);
        return batch.finalizedReport() != null ? batch.finalizedReport()
                : engine.evaluate(batch.dataset(), request(batch, batch.observations()));
    }

    public synchronized RunReport finalizeBatch(String id) throws IOException {
        Batch batch = get(id);
        if (batch.finalizedReport() != null) { return store.saveReport(batch.finalizedReport()); }
        if (batch.receivedObservations() != batch.expectedObservations()) {
            throw new EvaluationStore.ConflictException("批次仍有缺失观测，不能完成");
        }
        RunReport report = engine.evaluate(batch.dataset(), request(batch, batch.observations()));
        save(new Batch(batch.id(), batch.createdAt(), batch.dataset(), batch.repetitions(), batch.experiment(),
                batch.expectedObservations(), batch.receivedObservations(), BatchStatus.FINALIZED,
                batch.observations(), report));
        return store.saveReport(report);
    }

    private RunRequest request(Batch batch, List<Capture> captures) {
        return new RunRequest(batch.dataset().id(), batch.dataset().version(), batch.repetitions(),
                batch.experiment(), captures.stream().map(Capture::observation).toList());
    }

    private Path path(String id) {
        EvaluationEngine.identifier(id);
        return root.resolve(id + ".json");
    }

    private void save(Batch batch) throws IOException {
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, ".pending-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), batch);
            Files.move(temporary, path(batch.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
