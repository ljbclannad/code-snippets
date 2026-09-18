package com.lx.governance.evaluation;

import java.util.Locale;
import static com.lx.governance.evaluation.EvaluationModels.*;

public final class ReportRenderer {
    private ReportRenderer() { }

    public static String markdown(RunReport report) {
        Summary s = report.summary();
        StringBuilder text = new StringBuilder("# 评测报告\n\n");
        text.append("- 运行：").append(report.id()).append("\n")
                .append("- 样本集：").append(report.dataset().id()).append(" / ").append(report.dataset().version()).append("\n")
                .append("- 样本快照：").append(report.datasetHash()).append("\n")
                .append("- 评分器：").append(report.graderVersion()).append("\n")
                .append("- 实验：").append(escape(report.request().experiment().name())).append("\n")
                .append("- 数据来源：").append(report.request().experiment().source()).append("（由导入方声明）\n\n")
                .append("本报告仅验证确定性规则；未进行模型语义评分或医学正确性审核，也未重新执行模型或工具。\n\n")
                .append("总计 ").append(s.total()).append("，通过 ").append(s.passed()).append("，失败 ").append(s.failed())
                .append("，执行错误 ").append(s.errors()).append("，安全规则失败样本 ").append(s.safetyFailures()).append("。\n\n")
                .append("通过率：").append(String.format(Locale.ROOT, "%.2f%%", s.passRate() * 100)).append("；Token 总量：")
                .append(s.totalTokens() == null ? "未知（存在未记录值）" : s.totalTokens()).append("；已记录样本平均耗时：")
                .append(s.meanLatencyMs() == null ? "未知" : s.meanLatencyMs() + " ms").append("。\n\n")
                .append("| 样本 | 次数 | 场景 | 结果 | 未通过规则 |\n|---|---:|---|---|---|\n");
        for (CaseResult result : report.results()) {
            String failures = result.status() == Status.ERROR ? "执行错误，详见受限 JSON 记录"
                    : String.join("；", result.findings().stream().filter(f -> !f.passed())
                            .map(f -> f.ruleId() + "（" + f.severity() + "）：" + f.description()).toList());
            text.append("| ").append(result.caseId()).append(" | ").append(result.trial()).append(" | ")
                    .append(escape(result.scenario())).append(" | ").append(result.status()).append(" | ")
                    .append(escape(failures)).append(" |\n");
        }
        return text.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("[", "\\[").replace("]", "\\]");
    }
}
