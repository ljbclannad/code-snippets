package com.lx.governance.nacossimulator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void stateEndpointReturnsSummaryCounts() throws Exception {
        mockMvc.perform(get("/api/admin/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceCount").exists())
                .andExpect(jsonPath("$.healthyInstanceCount").exists())
                .andExpect(jsonPath("$.pendingLongPollCount").exists());
    }

    @Test
    void adminConfigsReturnsPublishedConfigs() throws Exception {
        mockMvc.perform(post("/api/config/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataId":"common.yml","group":"DEFAULT_GROUP","content":"gateway.rate=5"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dataId").value("common.yml"))
                .andExpect(jsonPath("$[0].group").value("DEFAULT_GROUP"));
    }

    @Test
    void adminInstancesReturnsRegisteredInstances() throws Exception {
        mockMvc.perform(post("/api/registry/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceName":"order-service","ip":"127.0.0.1","port":8081,"metadata":{"zone":"A"},"ttlMillis":15000}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceName").value("order-service"))
                .andExpect(jsonPath("$[0].instanceId").value("order-service#127.0.0.1#8081"));
    }

    @Test
    void adminLongPollsReturnsPendingListeners() throws Exception {
        mockMvc.perform(post("/api/config/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataId":"common.yml","group":"DEFAULT_GROUP","content":"gateway.rate=5"}
                                """))
                .andExpect(status().isOk());

        String currentMd5 = JsonPath.read(
                mockMvc.perform(get("/api/config/get")
                                .param("dataId", "common.yml")
                                .param("group", "DEFAULT_GROUP"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.md5");

        mockMvc.perform(post("/api/config/listener")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"client-a","probes":[{"dataId":"common.yml","group":"DEFAULT_GROUP","md5":"stale-md5"}]}
                                """))
                .andExpect(status().isOk());

        CompletableFuture<Void> pendingRequest = CompletableFuture.runAsync(() -> {
            try {
                mockMvc.perform(post("/api/config/listener")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"clientId":"client-b","probes":[{"dataId":"common.yml","group":"DEFAULT_GROUP","md5":"%s"}]}
                                        """.formatted(currentMd5)))
                        .andExpect(status().isOk());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        TimeUnit.MILLISECONDS.sleep(200);

        mockMvc.perform(get("/api/admin/long-polls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value("client-b"))
                .andExpect(jsonPath("$[0].watchKeys[0].dataId").value("common.yml"));

        mockMvc.perform(post("/api/config/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataId":"common.yml","group":"DEFAULT_GROUP","content":"gateway.rate=6"}
                                """))
                .andExpect(status().isOk());

        pendingRequest.get(2, TimeUnit.SECONDS);
        assertThat(pendingRequest).isCompleted();
    }
}
