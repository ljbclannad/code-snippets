package com.example.mcp;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("mcp-server")
public class WeatherMcpServerConfig {

    @Bean
    public WeatherMcpTools weatherMcpTools() {
        return new WeatherMcpTools();
    }

    public static class WeatherMcpTools {

        @McpTool(name = "get_weather", description = "Get demo weather information for a city", title = "Demo Weather")
        public WeatherResponse getWeather(
                @McpToolParam(description = "City name, for example Hangzhou, Beijing or Shanghai") String city) {
            String normalizedCity = city == null || city.isBlank() ? "杭州" : city.trim();
            return switch (normalizedCity) {
                case "北京" -> new WeatherResponse(normalizedCity, "晴", 18, 32, "西北风 3 级", "空气干燥，适合穿薄外套。");
                case "上海" -> new WeatherResponse(normalizedCity, "小雨", 21, 78, "东南风 2 级", "记得带伞，穿轻便防水外套。");
                case "深圳" -> new WeatherResponse(normalizedCity, "多云", 27, 70, "南风 2 级", "天气偏热，建议短袖并注意补水。");
                default -> new WeatherResponse(normalizedCity, "多云", 22, 61, "东北风 2 级", "早晚稍凉，建议薄外套。");
            };
        }
    }

    public record WeatherResponse(
            String city,
            String condition,
            int temperatureCelsius,
            int humidity,
            String wind,
            String suggestion) {
    }
}
