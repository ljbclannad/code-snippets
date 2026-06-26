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
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishThenGetReturnsLatestConfig() throws Exception {
        mockMvc.perform(post("/api/config/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataId":"common.yml","group":"DEFAULT_GROUP","content":"gateway.rate=5"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/config/get")
                        .param("dataId", "common.yml")
                        .param("group", "DEFAULT_GROUP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("gateway.rate=5"));
    }
}
