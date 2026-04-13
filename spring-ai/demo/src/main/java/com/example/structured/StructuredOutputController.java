package com.example.structured;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构化输出示例。
 */
@RestController
@RequestMapping("/structured-output")
public class StructuredOutputController {

    private final ChatClient chatClient;

    public StructuredOutputController(@Qualifier("DeepSeekChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    record BookSummary(String title, String author, String summary, List<String> keywords) {
    }

    record LearningStep(String title, String goal, List<String> actions) {
    }

    record TravelPlan(String city, int days, List<String> highlights) {
    }

    record FormatView(String converter, String format) {
    }

    record ResponseEntityView<T>(T entity, String rawContent) {
    }

    /**
     * 使用 entity(Class) 把模型输出转换成 Java Record。
     */
    @GetMapping("/bean")
    BookSummary bean(@RequestParam(defaultValue = "Effective Java") String book) {
        return chatClient.prompt()
                .user("请介绍《" + book + "》，包含书名、作者、100字以内简介和3个关键词。")
                .call()
                .entity(BookSummary.class);
    }

    /**
     * 使用 ParameterizedTypeReference 返回 List<Record>。
     */
    @GetMapping("/bean-list")
    List<LearningStep> beanList(@RequestParam(defaultValue = "Spring AI") String topic) {
        return chatClient.prompt()
                .user("请为学习 " + topic + " 生成3个学习步骤。每个步骤包含标题、目标和行动项。")
                .call()
                .entity(new ParameterizedTypeReference<List<LearningStep>>() {
                });
    }

    /**
     * 使用 ListOutputConverter 返回字符串列表。
     */
    @GetMapping("/list")
    List<String> list(@RequestParam(defaultValue = "Spring AI Structured Output") String topic) {
        ListOutputConverter converter = new ListOutputConverter();

        return chatClient.prompt()
                .user("请列出5个 " + topic + " 的常见使用场景。")
                .call()
                .entity(converter);
    }

    /**
     * 使用 MapOutputConverter 返回 Map。
     */
    @GetMapping("/map")
    Map<String, Object> map(@RequestParam(defaultValue = "ChatClient") String concept) {
        MapOutputConverter converter = new MapOutputConverter();

        return chatClient.prompt()
                .user("请解释 " + concept + "，包含 definition、advantages、pitfalls 三个字段。")
                .call()
                .entity(converter);
    }

    /**
     * 获取结构化输出格式说明。可把 format 放入自定义 PromptTemplate 中提示模型按格式输出。
     */
    @GetMapping("/format")
    FormatView format() {
        BeanOutputConverter<TravelPlan> converter = new BeanOutputConverter<>(TravelPlan.class);

        return new FormatView("BeanOutputConverter<TravelPlan>", converter.getFormat());
    }

    /**
     * 同时拿到原始 ChatResponse 和转换后的实体。
     */
    @GetMapping("/response-entity")
    ResponseEntityView<TravelPlan> responseEntity(@RequestParam(defaultValue = "杭州") String city) {
        ResponseEntity<ChatResponse, TravelPlan> responseEntity = chatClient.prompt()
                .user("请为 " + city + " 生成一个3天旅行计划，包含城市、天数和亮点列表。")
                .call()
                .responseEntity(TravelPlan.class);

        String rawContent = responseEntity.response().getResult().getOutput().getText();

        return new ResponseEntityView<>(responseEntity.entity(), rawContent);
    }

    /**
     * 不调用模型，直接把 JSON 字符串转换成 Java Record。
     */
    @GetMapping("/convert-local")
    TravelPlan convertLocal() {
        BeanOutputConverter<TravelPlan> converter = new BeanOutputConverter<>(TravelPlan.class);
        String json = """
                {
                  "city": "上海",
                  "days": 2,
                  "highlights": ["外滩", "上海博物馆", "武康路"]
                }
                """;

        return converter.convert(json);
    }

}
