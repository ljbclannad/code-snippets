package com.medical.report.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI medicalReportOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Medical Report API")
                        .description("基于 Spring AI 和 DeepSeek 的医疗报告推荐系统 API")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Medical Report Team")
                                .email("support@medical-report.com")));
    }
}
