package com.lx.governance.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.lx.governance.evaluation.EvaluationModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class EvaluationApiTest {
    private static final Path DATA_DIRECTORY = temporaryDirectory();
    private static final String PRIVATE_INPUT = "<script>privateInput()</script>";
    private static final String PRIVATE_OUTPUT = "<script>privateAnswer()</script>";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private EvaluationEngine engine;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("evaluation.storage-directory", DATA_DIRECTORY::toString);
    }

    @Test
    void datasetApiCreatesImmutableVersionsAndReportsMissingRecords() throws Exception {
        Dataset dataset = dataset();
        mvc.perform(post("/api/evaluation/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(dataset)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(dataset.id()));
        mvc.perform(post("/api/evaluation/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(dataset)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/evaluation/datasets/{id}/{version}", dataset.id(), "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].id").value("case1"));
        mvc.perform(get("/api/evaluation/datasets/unknown/v1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/evaluation/runs/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedDatasetFieldsReturnBadRequest() throws Exception {
        Dataset dataset = dataset();
        var invalidPointer = mapper.valueToTree(dataset);
        ((ObjectNode) invalidPointer.at("/cases/0/rules/0"))
                .put("path", "reply");
        mvc.perform(post("/api/evaluation/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(invalidPointer)))
                .andExpect(status().isBadRequest());
        var invalidEnum = mapper.valueToTree(dataset);
        ((ObjectNode) invalidEnum.at("/cases/0/rules/0"))
                .put("operator", "UNKNOWN");
        mvc.perform(post("/api/evaluation/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(invalidEnum)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/evaluation/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"invalid\",\"version\":\"v1\",\"cases\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsComparesAndReloadsPersistedRunsWithoutExposingRawObservationsInMarkdown() throws Exception {
        Dataset dataset = dataset();
        mvc.perform(post("/api/evaluation/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(dataset)))
                .andExpect(status().isCreated());
        RunReport baseline = createRun(dataset, false);
        RunReport candidate = createRun(dataset, true);

        mvc.perform(get("/api/evaluation/runs/{id}", candidate.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.passed").value(1));
        mvc.perform(get("/api/evaluation/comparisons")
                        .param("baseline", baseline.id()).param("candidate", candidate.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatePassed").value(true))
                .andExpect(jsonPath("$.improvements.length()").value(1));

        String markdown = mvc.perform(get("/api/evaluation/runs/{id}/report", candidate.id()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/markdown")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(markdown.contains("评测报告"));
        assertTrue(markdown.contains(candidate.id()));
        assertFalse(markdown.contains(PRIVATE_INPUT));
        assertFalse(markdown.contains(PRIVATE_OUTPUT));
        assertFalse(markdown.contains("privateInput"));
        assertFalse(markdown.contains("privateAnswer"));
        assertFalse(markdown.contains("<script>"));

        EvaluationStore restarted = new EvaluationStore(DATA_DIRECTORY.toString(), mapper, engine);
        RunReport persisted = restarted.report(candidate.id());
        assertEquals(candidate, persisted);
        assertEquals(dataset, restarted.dataset(dataset.id(), "v1"));
        assertTrue(restarted.reports().stream().anyMatch(report -> report.id().equals(candidate.id())));
        assertEquals(PRIVATE_OUTPUT, persisted.request().observations().get(0).output().path("reply").asText());
    }

    private RunReport createRun(Dataset dataset, boolean passed) throws Exception {
        Observation observation = new Observation("case1", 1, dataset.cases().get(0).input(),
                mapper.createObjectNode().put("valid", passed).put("reply", PRIVATE_OUTPUT), 12L, 7L, null);
        RunRequest request = new RunRequest(dataset.id(), dataset.version(), 1,
                new Experiment("接口回归", "mock", "v1", "v1", Source.MOCK, mapper.createObjectNode()),
                List.of(observation));
        byte[] body = mvc.perform(post("/api/evaluation/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        return mapper.readValue(body, RunReport.class);
    }

    private Dataset dataset() {
        Rule rule = new Rule("valid", "验证回答状态", "/valid", Operator.EQUALS,
                mapper.valueToTree(true), null, Severity.QUALITY);
        Sample sample = new Sample("case1", "接口验证", mapper.createObjectNode().put("message", PRIVATE_INPUT), List.of(rule));
        return new Dataset("api-" + UUID.randomUUID(), "v1", "隔离的接口测试样本", List.of(sample));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("evaluation-api-test-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建隔离测试目录", exception);
        }
    }
}
