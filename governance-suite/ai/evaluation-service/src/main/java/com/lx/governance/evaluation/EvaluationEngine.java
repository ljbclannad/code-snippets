package com.lx.governance.evaluation;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.lx.governance.evaluation.EvaluationModels.*;

/** 确定性规则评测，不将关键词匹配解释为医学正确性或语义评测。 */
@Component
public class EvaluationEngine {
    public static final String GRADER_VERSION = "deterministic-v1";
    private final ObjectMapper mapper;

    public EvaluationEngine(ObjectMapper mapper) { this.mapper = mapper; }

    public void validateDataset(Dataset dataset) {
        require(dataset != null, "样本集不能为空");
        identifier(dataset.id());
        identifier(dataset.version());
        require(dataset.cases() != null && !dataset.cases().isEmpty() && dataset.cases().size() <= 500,
                "样本数必须为 1 至 500");
        var ids = new HashSet<String>();
        for (Sample sample : dataset.cases()) {
            require(sample != null, "样本不能为空");
            identifier(sample.id());
            require(ids.add(sample.id()), "样本 ID 重复");
            require(sample.input() != null && sample.input().isObject(), "样本 input 必须为 JSON 对象");
            require(sample.scenario() != null && !sample.scenario().isBlank(), "场景不能为空");
            require(sample.rules() != null && !sample.rules().isEmpty() && sample.rules().size() <= 50,
                    "每条样本必须有 1 至 50 条规则");
            var ruleIds = new HashSet<String>();
            for (Rule rule : sample.rules()) {
                require(rule != null, "规则不能为空");
                identifier(rule.id());
                require(ruleIds.add(rule.id()), "规则 ID 重复");
                pointer(rule.path());
                require(rule.operator() != null && rule.severity() != null, "规则类型和严重程度不能为空");
                require(rule.description() != null && !rule.description().isBlank(), "规则说明不能为空");
                switch (rule.operator()) {
                    case EQUALS -> require(rule.expected() != null, "EQUALS 缺少 expected");
                    case CONTAINS, NOT_CONTAINS -> require(rule.expected() != null && rule.expected().isTextual()
                            && !rule.expected().asText().isBlank(), "文本规则需要非空字符串 expected");
                    case EQUALS_FIELD, SUBSET_OF_FIELD -> pointer(rule.referencePath());
                    case EXISTS -> { }
                }
            }
        }
    }

    public RunReport evaluate(Dataset dataset, RunRequest request) {
        validateDataset(dataset);
        require(request != null, "运行请求不能为空");
        require(dataset.id().equals(request.datasetId()) && dataset.version().equals(request.datasetVersion()),
                "样本集标识或版本不匹配");
        require(request.repetitions() >= 1 && request.repetitions() <= 10, "重复次数必须为 1 至 10");
        Experiment experiment = request.experiment();
        require(experiment != null && experiment.source() != null, "实验和来源不能为空");
        require(nonblank(experiment.name()) && nonblank(experiment.model()) && nonblank(experiment.promptVersion())
                && nonblank(experiment.knowledgeVersion()), "必须记录实验名、模型、提示词和知识版本；未知请显式填 unknown");
        require(experiment.configuration() != null && experiment.configuration().isObject(), "实验 configuration 必须为对象");
        require(request.observations() != null && request.observations().size() <= dataset.cases().size() * request.repetitions(),
                "观测数量超限或为空");
        Map<String, Sample> samples = new HashMap<>();
        dataset.cases().forEach(s -> samples.put(s.id(), s));
        Map<String, Observation> observations = new HashMap<>();
        for (Observation observation : request.observations()) {
            require(observation != null && samples.containsKey(observation.caseId()), "观测引用未知样本");
            require(observation.trial() >= 1 && observation.trial() <= request.repetitions(), "观测 trial 越界");
            require(samples.get(observation.caseId()).input().equals(observation.input()), "观测输入与样本不一致");
            require(observation.latencyMs() == null || observation.latencyMs() >= 0, "耗时不能为负");
            require(observation.totalTokens() == null || observation.totalTokens() >= 0, "Token 数不能为负");
            require(observations.putIfAbsent(key(observation.caseId(), observation.trial()), observation) == null,
                    "同一样本的 trial 重复");
        }
        List<CaseResult> results = new ArrayList<>();
        for (Sample sample : dataset.cases()) {
            for (int trial = 1; trial <= request.repetitions(); trial++) {
                Observation observation = observations.get(key(sample.id(), trial));
                String error = observation == null ? "缺少运行观测" : observation.error();
                if (!nonblank(error) && (observation.output() == null || !observation.output().isObject())) {
                    error = "缺少 JSON 对象输出";
                }
                if (nonblank(error)) {
                    results.add(new CaseResult(sample.id(), trial, sample.scenario(), Status.ERROR, List.of(), error, observation));
                    continue;
                }
                List<Finding> findings = sample.rules().stream().map(r -> grade(r, observation.output())).toList();
                Status status = findings.stream().allMatch(Finding::passed) ? Status.PASS : Status.FAIL;
                results.add(new CaseResult(sample.id(), trial, sample.scenario(), status, findings, null, observation));
            }
        }
        return new RunReport(UUID.randomUUID().toString(), Instant.now().toString(), hash(dataset), GRADER_VERSION,
                dataset, request, summarize(results), results);
    }

    private Finding grade(Rule rule, JsonNode output) {
        JsonNode actual = output.at(rule.path());
        JsonNode expected = switch (rule.operator()) {
            case EQUALS_FIELD, SUBSET_OF_FIELD -> output.at(rule.referencePath());
            default -> rule.expected();
        };
        boolean present = !actual.isMissingNode() && !actual.isNull();
        boolean passed = present && switch (rule.operator()) {
            case EXISTS -> true;
            case EQUALS, EQUALS_FIELD -> actual.equals(expected);
            case CONTAINS -> actual.isTextual() && actual.asText().contains(expected.asText());
            case NOT_CONTAINS -> actual.isTextual() && !actual.asText().contains(expected.asText());
            case SUBSET_OF_FIELD -> subset(actual, expected);
        };
        return new Finding(rule.id(), rule.severity(), passed, rule.description(), actual, expected);
    }

    private boolean subset(JsonNode actual, JsonNode expected) {
        if (expected == null || !actual.isArray() || !expected.isArray()) { return false; }
        var allowed = new HashSet<JsonNode>();
        expected.forEach(allowed::add);
        for (JsonNode value : actual) { if (!allowed.contains(value)) { return false; } }
        return true;
    }

    private Summary summarize(List<CaseResult> results) {
        int passed = 0, failed = 0, errors = 0, safety = 0, latencyCount = 0;
        double latency = 0;
        long tokens = 0;
        boolean completeTokens = true;
        for (CaseResult result : results) {
            switch (result.status()) { case PASS -> passed++; case FAIL -> failed++; case ERROR -> errors++; }
            if (result.findings().stream().anyMatch(f -> f.severity() == Severity.SAFETY && !f.passed())) { safety++; }
            Observation observation = result.observation();
            if (observation == null || observation.totalTokens() == null) { completeTokens = false; }
            else {
                try { tokens = Math.addExact(tokens, observation.totalTokens()); }
                catch (ArithmeticException e) { throw new IllegalArgumentException("Token 总量超出范围"); }
            }
            if (observation != null && observation.latencyMs() != null) {
                latency += observation.latencyMs(); latencyCount++;
            }
        }
        return new Summary(results.size(), passed, failed, errors, safety, (double) passed / results.size(),
                completeTokens ? tokens : null, latencyCount == 0 ? null : latency / latencyCount);
    }

    public Comparison compare(RunReport baseline, RunReport candidate) {
        require(baseline.datasetHash().equals(candidate.datasetHash())
                && baseline.graderVersion().equals(candidate.graderVersion())
                && baseline.request().repetitions() == candidate.request().repetitions()
                && baseline.request().experiment().source() == candidate.request().experiment().source(),
                "只能比较同一样本快照、评分器、重复次数及来源模式的运行");
        Map<String, CaseResult> previous = new HashMap<>();
        baseline.results().forEach(r -> previous.put(key(r.caseId(), r.trial()), r));
        List<Change> improvements = new ArrayList<>(), regressions = new ArrayList<>(), safety = new ArrayList<>();
        for (CaseResult next : candidate.results()) {
            CaseResult old = previous.get(key(next.caseId(), next.trial()));
            require(old != null, "对比结果缺少匹配样本");
            Change change = new Change(next.caseId(), next.trial(), old.status(), next.status());
            if (old.status() != Status.PASS && next.status() == Status.PASS) { improvements.add(change); }
            Map<String, Finding> oldFindings = new HashMap<>();
            old.findings().forEach(f -> oldFindings.put(f.ruleId(), f));
            boolean ruleRegression = next.findings().stream().anyMatch(f -> !f.passed()
                    && oldFindings.containsKey(f.ruleId()) && oldFindings.get(f.ruleId()).passed());
            if ((old.status() == Status.PASS && next.status() != Status.PASS)
                    || (old.status() != Status.ERROR && next.status() == Status.ERROR) || ruleRegression) {
                regressions.add(change);
            }
            if (next.findings().stream().anyMatch(f -> f.severity() == Severity.SAFETY && !f.passed()
                    && oldFindings.containsKey(f.ruleId()) && oldFindings.get(f.ruleId()).passed())) { safety.add(change); }
        }
        List<String> reasons = new ArrayList<>();
        if (!regressions.isEmpty()) { reasons.add("存在样本或规则退步"); }
        if (candidate.summary().safetyFailures() > 0) { reasons.add("候选存在安全规则失败"); }
        if (candidate.summary().errors() > 0) { reasons.add("候选存在执行错误或缺失观测"); }
        if (candidate.summary().failed() > 0) { reasons.add("候选尚未通过全部确定性规则"); }
        return new Comparison(baseline.id(), candidate.id(), candidate.summary().passRate() - baseline.summary().passRate(),
                improvements, regressions, safety, reasons.isEmpty(), reasons);
    }

    public String hash(Dataset dataset) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(dataset).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法计算样本快照摘要", e); }
    }

    public static void identifier(String value) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"), "标识只能包含字母、数字、点、横线和下划线，最长 80 字符");
    }
    private static void pointer(String value) {
        require(value != null && (value.isEmpty() || value.startsWith("/")), "字段路径必须是 JSON Pointer");
        require(!value.matches(".*~(?![01]).*"), "JSON Pointer 转义无效");
        JsonPointer.compile(value);
    }
    private static String key(String id, int trial) { return id + ":" + trial; }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
