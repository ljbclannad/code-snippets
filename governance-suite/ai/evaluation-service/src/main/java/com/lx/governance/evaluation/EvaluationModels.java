package com.lx.governance.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 评测协议独立于业务 DTO；input/output 可直接承载对话响应或 Agent 回放。 */
public final class EvaluationModels {
    private EvaluationModels() { }

    public enum Operator { EXISTS, EQUALS, CONTAINS, NOT_CONTAINS, EQUALS_FIELD, SUBSET_OF_FIELD }
    public enum Severity { QUALITY, SAFETY }
    public enum Status { PASS, FAIL, ERROR }
    public enum Source { MOCK, RECORDED_REAL }

    public record Rule(String id, String description, String path, Operator operator,
                       JsonNode expected, String referencePath, Severity severity) { }
    public record Sample(String id, String scenario, JsonNode input, List<Rule> rules) { }
    public record Dataset(String id, String version, String description, List<Sample> cases) { }
    public record Experiment(String name, String model, String promptVersion, String knowledgeVersion,
                             Source source, JsonNode configuration) { }
    public record Observation(String caseId, int trial, JsonNode input, JsonNode output,
                              Long latencyMs, Long totalTokens, String error) { }
    public record RunRequest(String datasetId, String datasetVersion, int repetitions,
                             Experiment experiment, List<Observation> observations) { }
    public record Finding(String ruleId, Severity severity, boolean passed, String description,
                          JsonNode actual, JsonNode expected) { }
    public record CaseResult(String caseId, int trial, String scenario, Status status,
                             List<Finding> findings, String error, Observation observation) { }
    public record Summary(int total, int passed, int failed, int errors, int safetyFailures,
                          double passRate, Long totalTokens, Double meanLatencyMs) { }
    public record RunReport(String id, String createdAt, String datasetHash, String graderVersion,
                            Dataset dataset, RunRequest request, Summary summary, List<CaseResult> results) { }
    public record Change(String caseId, int trial, Status baseline, Status candidate) { }
    public record Comparison(String baselineId, String candidateId, double passRateDelta,
                             List<Change> improvements, List<Change> regressions,
                             List<Change> safetyRegressions, boolean gatePassed, List<String> reasons) { }
}
