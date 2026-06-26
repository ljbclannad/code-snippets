package com.lx.governance.nacossimulator.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RegistryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerThenListReturnsHealthyInstance() throws Exception {
        mockMvc.perform(post("/api/registry/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceName":"order-service","ip":"127.0.0.1","port":8081,"metadata":{"zone":"A"},"ttlMillis":15000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value("order-service#127.0.0.1#8081"));

        mockMvc.perform(get("/api/registry/instances")
                        .param("serviceName", "order-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].instanceId").value("order-service#127.0.0.1#8081"));
    }
}
