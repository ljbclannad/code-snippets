package com.lx.governance.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.lx.governance.evaluation.EvaluationModels.*;
import static com.lx.governance.evaluation.EvaluationBatchService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvaluationBatchTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final EvaluationEngine engine = new EvaluationEngine(mapper);
    EvaluationStore store;
    EvaluationBatchService batches;
    Dataset dataset;

    @BeforeEach
    void setup() throws Exception {
        store = new EvaluationStore(directory.toString(), mapper, engine);
        batches = new EvaluationBatchService(directory.toString(), mapper, engine, store);
        dataset = new Dataset("capture", "v1", "采集验证", List.of(new Sample("case1", "回答",
                mapper.createObjectNode().put("message", "测试问题"),
                List.of(new Rule("reply", "应有回复", "/reply", Operator.EXISTS, null, null, Severity.QUALITY)))));
        store.createDataset(dataset);
    }

    Batch create(int repetitions) throws Exception {
        return batches.create(new CreateBatch(dataset.id(), dataset.version(), repetitions,
                new Experiment("采集", "unknown", "unknown", "unknown", Source.RECORDED_REAL, mapper.createObjectNode())));
    }

    Capture capture(String id, int trial, String error) {
        return new Capture(id, "case1", trial, dataset.cases().get(0).input(),
                mapper.createObjectNode().put("reply", "测试回复"), 5L, null, error);
    }

    @Test
    void missingObservationsRemainErrorsAndCannotFinalize() throws Exception {
        Batch batch = create(2);
        assertEquals(2, batches.report(batch.id()).summary().errors());
        assertTrue(store.reports().isEmpty());
        assertThrows(EvaluationStore.ConflictException.class, () -> batches.finalizeBatch(batch.id()));
        batches.capture(batch.id(), capture("first", 1, null));
        RunReport report = batches.report(batch.id());
        assertEquals(1, report.summary().passed());
        assertEquals(1, report.summary().errors());
        assertEquals(BatchStatus.COLLECTING, batches.get(batch.id()).status());
    }

    @Test
    void capturesAndFinalReportSurviveRestartAndFinalizationIsIdempotent() throws Exception {
        Batch batch = create(2);
        Capture first = capture("first", 1, null);
        batches.capture(batch.id(), first);
        batches = new EvaluationBatchService(directory.toString(), mapper, engine, store);
        assertEquals(1, batches.get(batch.id()).receivedObservations());
        assertEquals(1, batches.capture(batch.id(), first).receivedObservations());
        batches.capture(batch.id(), capture("second", 2, "模型超时"));
        assertEquals(BatchStatus.READY, batches.get(batch.id()).status());
        RunReport report = batches.finalizeBatch(batch.id());
        assertEquals(1, report.summary().errors());
        assertEquals("模型超时", report.results().get(1).error());
        batches = new EvaluationBatchService(directory.toString(), mapper, engine, store);
        assertEquals(report, batches.finalizeBatch(batch.id()));
        assertEquals(report, batches.report(batch.id()));
        assertEquals(report, store.report(report.id()));
        assertEquals(1, store.reports().size());
        assertEquals(BatchStatus.FINALIZED, batches.capture(batch.id(), first).status());
        assertThrows(EvaluationStore.ConflictException.class,
                () -> batches.capture(batch.id(), capture("late", 3, null)));
    }

    @Test
    void rejectsDuplicateIdentityAndInvalidSampleInputs() throws Exception {
        Batch batch = create(1);
        Capture first = capture("first", 1, null);
        batches.capture(batch.id(), first);
        assertThrows(EvaluationStore.ConflictException.class,
                () -> batches.capture(batch.id(), capture("first", 1, "内容变化")));
        assertThrows(EvaluationStore.ConflictException.class,
                () -> batches.capture(batch.id(), capture("different", 1, null)));
        Batch empty = create(1);
        assertThrows(IllegalArgumentException.class, () -> batches.capture(empty.id(), capture("bad-trial", 2, null)));
        assertThrows(IllegalArgumentException.class, () -> batches.capture(empty.id(), new Capture("unknown", "unknown", 1,
                first.input(), first.output(), null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> batches.capture(empty.id(), new Capture("mismatch", "case1", 1,
                mapper.createObjectNode(), first.output(), null, null, null)));
        assertEquals(0, batches.get(empty.id()).receivedObservations());
    }

    @Test
    void rejectsMockSource() {
        assertThrows(IllegalArgumentException.class, () -> batches.create(new CreateBatch("capture", "v1", 1,
                new Experiment("采集", "mock", "v1", "v1", Source.MOCK, mapper.createObjectNode()))));
    }

    @Test
    void concurrentRetriesOnlyRecordOneObservation() throws Exception {
        Batch batch = create(1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            Callable<Batch> submit = () -> batches.capture(batch.id(), capture("same", 1, null));
            for (var result : executor.invokeAll(List.of(submit, submit, submit, submit))) {
                assertEquals(1, result.get().receivedObservations());
            }
            assertEquals(1, batches.get(batch.id()).observations().size());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void batchHttpApiReturnsExpectedStatusesAndReport() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(store, engine, batches)).build();
        var request = new CreateBatch(dataset.id(), dataset.version(), 1,
                new Experiment("入口", "unknown", "unknown", "unknown", Source.RECORDED_REAL, mapper.createObjectNode()));
        byte[] body = mvc.perform(post("/api/evaluation/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        String id = mapper.readTree(body).get("id").asText();
        mvc.perform(get("/api/evaluation/batches/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COLLECTING"));
        mvc.perform(get("/api/evaluation/batches/unknown")).andExpect(status().isNotFound());
        mvc.perform(post("/api/evaluation/batches/{id}/finalize", id)).andExpect(status().isConflict());
        mvc.perform(post("/api/evaluation/batches/{id}/observations", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(capture("invalid", 2, null)))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/evaluation/batches/{id}/report", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.errors").value(1));
        mvc.perform(post("/api/evaluation/batches/{id}/observations", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(capture("valid", 1, null)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(post("/api/evaluation/batches/{id}/finalize", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.passed").value(1));
    }
}
