package com.lx.governance.evaluation;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import static com.lx.governance.evaluation.EvaluationModels.*;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {
    private final EvaluationStore store;
    private final EvaluationEngine engine;
    private final EvaluationBatchService batches;

    public EvaluationController(EvaluationStore store, EvaluationEngine engine, EvaluationBatchService batches) {
        this.store = store;
        this.engine = engine;
        this.batches = batches;
    }

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationBatchService.Batch createBatch(@RequestBody EvaluationBatchService.CreateBatch request) throws IOException {
        return batches.create(request);
    }

    @GetMapping("/batches/{id}")
    public EvaluationBatchService.Batch batch(@PathVariable String id) throws IOException { return batches.get(id); }

    @PostMapping("/batches/{id}/observations")
    public EvaluationBatchService.Batch capture(@PathVariable String id,
            @RequestBody EvaluationBatchService.Capture capture) throws IOException { return batches.capture(id, capture); }

    @GetMapping("/batches/{id}/report")
    public RunReport batchReport(@PathVariable String id) throws IOException { return batches.report(id); }

    @PostMapping("/batches/{id}/finalize")
    public RunReport finalizeBatch(@PathVariable String id) throws IOException { return batches.finalizeBatch(id); }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP", "graderVersion", EvaluationEngine.GRADER_VERSION); }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    public Dataset createDataset(@RequestBody Dataset dataset) throws IOException { return store.createDataset(dataset); }

    @GetMapping("/datasets")
    public List<Dataset> datasets() throws IOException { return store.datasets(); }

    @GetMapping("/datasets/{id}/{version}")
    public Dataset dataset(@PathVariable String id, @PathVariable String version) throws IOException {
        return store.dataset(id, version);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public RunReport createRun(@RequestBody RunRequest request) throws IOException { return store.createRun(request); }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() throws IOException {
        return store.reports().stream().map(r -> Map.<String, Object>of("id", r.id(), "createdAt", r.createdAt(),
                "datasetId", r.dataset().id(), "datasetVersion", r.dataset().version(),
                "experiment", r.request().experiment(), "summary", r.summary())).toList();
    }

    @GetMapping("/runs/{id}")
    public RunReport report(@PathVariable String id) throws IOException { return store.report(id); }

    @GetMapping(value = "/runs/{id}/report", produces = "text/markdown;charset=UTF-8")
    public String markdown(@PathVariable String id) throws IOException { return ReportRenderer.markdown(store.report(id)); }

    @GetMapping("/comparisons")
    public Comparison compare(@RequestParam String baseline, @RequestParam String candidate) throws IOException {
        return engine.compare(store.report(baseline), store.report(candidate));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", e instanceof HttpMessageNotReadableException
                ? "请求 JSON 无效或字段类型不正确" : e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(EvaluationStore.ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(EvaluationStore.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> storageFailure(IOException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "评测存储读写失败，请检查目录权限和磁盘状态"));
    }
}
