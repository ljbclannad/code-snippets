package com.medical.report.config;

import com.medical.report.function.PatientHistoryFunction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 PatientHistoryFunction patientHistoryFunction) {
        return chatClientBuilder
                .defaultSystem("You are a helpful medical report assistant. Please respond in Chinese.")
                .defaultFunctions("queryPatientHistory")
                .build();
    }

    @Bean
    public FunctionCallback patientHistoryFunctionCallback(PatientHistoryFunction function) {
        return FunctionCallbackWrapper.builder(function)
                .withName("queryPatientHistory")
                .withDescription("查询患者的历史医疗报告记录，用于了解患者的健康历史和趋势")
                .build();
    }
}
