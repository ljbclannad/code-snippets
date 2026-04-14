package com.medical.report.function;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.medical.report.entity.MedicalReportHistory;
import com.medical.report.mapper.MedicalReportHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component("queryPatientHistory")
public class PatientHistoryFunction implements Function<PatientHistoryFunction.Request, PatientHistoryFunction.Response> {

    private final MedicalReportHistoryMapper historyMapper;

    public PatientHistoryFunction(MedicalReportHistoryMapper historyMapper) {
        this.historyMapper = historyMapper;
    }

    @Override
    public Response apply(Request request) {
        log.info("Function called: queryPatientHistory for patient: {}", request.patientName());

        List<MedicalReportHistory> histories = historyMapper.findByPatientName(request.patientName());

        if (histories.isEmpty()) {
            return new Response("未找到患者 " + request.patientName() + " 的历史报告", List.of());
        }

        List<HistorySummary> summaries = histories.stream()
                .map(h -> new HistorySummary(
                        h.getId(),
                        h.getReportDate(),
                        h.getHealthAdvice(),
                        h.getRiskAssessment()
                ))
                .collect(Collectors.toList());

        return new Response("找到 " + histories.size() + " 条历史报告", summaries);
    }

    @JsonClassDescription("查询患者历史报告的请求")
    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("患者姓名")
            String patientName
    ) {}

    public record Response(
            String message,
            List<HistorySummary> histories
    ) {}

    public record HistorySummary(
            Long id,
            String reportDate,
            String healthAdvice,
            String riskAssessment
    ) {}
}
