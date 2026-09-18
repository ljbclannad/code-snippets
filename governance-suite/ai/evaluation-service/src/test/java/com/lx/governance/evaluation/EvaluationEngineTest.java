package com.lx.governance.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lx.governance.evaluation.EvaluationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvaluationEngine engine = new EvaluationEngine(mapper);

    @Test
    void evaluatesAllOperatorsAgainstRecordedOutput() throws Exception {
        Dataset dataset = dataset(List.of(
                rule("exists", "/reply", Operator.EXISTS, null, null),
                rule("equals", "/sent", Operator.EQUALS, json("false"), null),
                rule("contains", "/reply", Operator.CONTAINS, json("\"等待审批\""), null),
                rule("not-contains", "/reply", Operator.NOT_CONTAINS, json("\"已发送\""), null),
                rule("same", "/citations", Operator.EQUALS_FIELD, null, "/usedEvidence"),
                rule("subset", "/citations", Operator.SUBSET_OF_FIELD, null, "/evidence")));
        RunReport report = engine.evaluate(dataset, request(1, List.of(observation(1,
                "{\"reply\":\"等待审批\",\"sent\":false,\"citations\":[\"doc1\"],"
                        + "\"usedEvidence\":[\"doc1\"],\"evidence\":[\"doc1\",\"doc2\"]}"))));
        assertEquals(Status.PASS, report.results().get(0).status());
        assertEquals(6, report.results().get(0).findings().size());
        assertEquals(1, report.summary().passed());
    }

    @Test
    void missingFieldCannotPassEvenNegativeAssertion() {
        for (Operator operator : Operator.values()) {
            Rule rule = rule("check", "/missing", operator,
                    operator == Operator.EQUALS || operator == Operator.CONTAINS
                            || operator == Operator.NOT_CONTAINS ? mapper.valueToTree("unsafe") : null,
                    operator == Operator.EQUALS_FIELD || operator == Operator.SUBSET_OF_FIELD ? "/reference" : null);
            RunReport report = engine.evaluate(dataset(List.of(rule)), request(1,
                    List.of(observation(1, "{\"reference\":[\"unsafe\"]}"))));
            assertEquals(Status.FAIL, report.results().get(0).status(), operator.name());
        }
    }

    @Test
    void missingTrialsAndReportedErrorsAreCountedAsExecutionErrors() {
        Observation error = new Observation("case1", 1, input(), null, 20L, null, "模型调用超时");
        RunReport report = engine.evaluate(basicDataset(), request(3, List.of(error)));
        assertEquals(3, report.results().size());
        assertEquals(List.of(1, 2, 3), report.results().stream().map(CaseResult::trial).toList());
        assertEquals(3, report.summary().total());
        assertEquals(3, report.summary().errors());
        assertEquals(0, report.summary().passed());
        assertTrue(report.results().stream().allMatch(result -> result.status() == Status.ERROR));
    }

    @Test
    void rejectsObservationsWhichCannotBeMatchedUnambiguously() {
        Observation normal = observation(1, "{\"reply\":\"ok\"}");
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(basicDataset(), request(1, List.of(normal, normal))));
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(basicDataset(), request(1, List.of(
                        new Observation("unknown", 1, input(), normal.output(), 10L, 5L, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(basicDataset(), request(1, List.of(
                        new Observation("case1", 1, mapper.valueToTree("changed"), normal.output(), 10L, 5L, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(basicDataset(), request(1, List.of(observation(2, "{}")))));
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate(basicDataset(), request(0, List.of())));
    }

    @Test
    void rejectsEmptyDuplicateAndMalformedDatasetDefinitions() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.validateDataset(new Dataset("test", "v1", "空数据", List.of())));
        assertThrows(IllegalArgumentException.class, () -> engine.validateDataset(dataset(List.of())));
        Sample sample = basicDataset().cases().get(0);
        assertThrows(IllegalArgumentException.class,
                () -> engine.validateDataset(new Dataset("test", "v1", "重复样本", List.of(sample, sample))));
        Rule normal = rule("same", "/reply", Operator.EXISTS, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> engine.validateDataset(dataset(List.of(normal, normal))));
        assertInvalid(rule("bad", "reply", Operator.EXISTS, null, null));
        assertInvalid(rule("bad", "/reply~2", Operator.EXISTS, null, null));
        assertInvalid(rule("bad", "/reply", Operator.EQUALS, null, null));
        assertInvalid(rule("bad", "/reply", Operator.CONTAINS, mapper.valueToTree(3), null));
        assertInvalid(rule("bad", "/reply", Operator.EQUALS_FIELD, null, null));
        assertInvalid(rule("bad", "/reply", Operator.SUBSET_OF_FIELD, null, "reference"));
    }

    @Test
    void detectsRuleRegressionEvenWhenBothCasesAlreadyFail() throws Exception {
        Dataset dataset = dataset(List.of(
                rule("first", "/a", Operator.EQUALS, json("true"), null),
                rule("second", "/b", Operator.EQUALS, json("true"), null)));
        RunReport baseline = engine.evaluate(dataset, request(1,
                List.of(observation(1, "{\"a\":true,\"b\":false}"))));
        RunReport candidate = engine.evaluate(dataset, request(1,
                List.of(observation(1, "{\"a\":false,\"b\":false}"))));
        Comparison comparison = engine.compare(baseline, candidate);
        assertEquals(Status.FAIL, baseline.results().get(0).status());
        assertEquals(Status.FAIL, candidate.results().get(0).status());
        assertFalse(comparison.regressions().isEmpty());
        assertFalse(comparison.gatePassed());
    }

    @Test
    void gateRejectsExistingSafetyFailureAndExecutionError() throws Exception {
        Rule safety = new Rule("safe", "禁止虚构成功", "/sent", Operator.EQUALS,
                json("false"), null, Severity.SAFETY);
        Dataset dataset = dataset(List.of(safety));
        RunReport unsafe = engine.evaluate(dataset, request(1,
                List.of(observation(1, "{\"sent\":true}"))));
        assertEquals(1, unsafe.summary().safetyFailures());
        assertFalse(engine.compare(unsafe, unsafe).gatePassed());
        RunReport error = engine.evaluate(dataset, request(1, List.of()));
        assertFalse(engine.compare(error, error).gatePassed());
    }

    @Test
    void comparisonAcceptsImprovementButRejectsIncompatibleRuns() {
        RunReport baseline = engine.evaluate(basicDataset(), request(1, List.of(observation(1, "{}"))));
        RunReport candidate = engine.evaluate(basicDataset(), request(1,
                List.of(observation(1, "{\"reply\":\"ok\"}"))));
        Comparison comparison = engine.compare(baseline, candidate);
        assertTrue(comparison.gatePassed());
        assertEquals(1, comparison.improvements().size());
        assertTrue(comparison.passRateDelta() > 0);
        assertThrows(IllegalArgumentException.class,
                () -> engine.compare(baseline, copy(candidate, "different", candidate.graderVersion(), candidate.request())));
        assertThrows(IllegalArgumentException.class,
                () -> engine.compare(baseline, copy(candidate, candidate.datasetHash(), "different", candidate.request())));
        RunRequest repetitions = request(2, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> engine.compare(baseline, copy(candidate, candidate.datasetHash(), candidate.graderVersion(), repetitions)));
        Experiment real = new Experiment("真实模型", "model", "v1", "v1", Source.RECORDED_REAL, mapper.createObjectNode());
        RunRequest source = new RunRequest("test", "v1", 1, real, candidate.request().observations());
        assertThrows(IllegalArgumentException.class,
                () -> engine.compare(baseline, copy(candidate, candidate.datasetHash(), candidate.graderVersion(), source)));
    }

    private RunReport copy(RunReport report, String hash, String graderVersion, RunRequest request) {
        return new RunReport(report.id(), report.createdAt(), hash, graderVersion,
                report.dataset(), request, report.summary(), report.results());
    }

    private void assertInvalid(Rule rule) {
        assertThrows(IllegalArgumentException.class, () -> engine.validateDataset(dataset(List.of(rule))));
    }

    private Dataset basicDataset() {
        return dataset(List.of(rule("answer", "/reply", Operator.EXISTS, null, null)));
    }

    private Dataset dataset(List<Rule> rules) {
        return new Dataset("test", "v1", "测试数据集", List.of(new Sample("case1", "知识问答", input(), rules)));
    }

    private JsonNode input() {
        return mapper.createObjectNode().put("message", "请解释当前结果");
    }

    private Rule rule(String id, String path, Operator operator, JsonNode expected, String referencePath) {
        return new Rule(id, "检查回答", path, operator, expected, referencePath, Severity.QUALITY);
    }

    private RunRequest request(int repetitions, List<Observation> observations) {
        return new RunRequest("test", "v1", repetitions,
                new Experiment("离线回归", "mock", "v1", "v1", Source.MOCK, mapper.createObjectNode()), observations);
    }

    private Observation observation(int trial, String output) {
        try {
            return new Observation("case1", trial, input(), json(output), 10L, 5L, null);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }
}
