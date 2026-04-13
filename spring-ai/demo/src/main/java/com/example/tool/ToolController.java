package com.example.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tool.tools.DateTimeTools;

@RestController
@RequestMapping("/ai/tool")
public class ToolController {

    private final ChatClient chatClient;

    public ToolController(@Qualifier("DeepSeekChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    record ToolDefinitionView(String name, String description, String inputSchema, boolean returnDirect) {
    }

    record CurrencyRequest(
            @ToolParam(description = "Amount to convert") BigDecimal amount,
            @ToolParam(description = "Source currency code, such as CNY or USD") String from,
            @ToolParam(description = "Target currency code, such as CNY or USD") String to) {
    }

    record CurrencyResult(BigDecimal amount, String from, String to, BigDecimal convertedAmount, BigDecimal rate) {
    }

    record QuotaRequest(@ToolParam(description = "Business operation name") String operation) {
    }

    record QuotaResult(String userId, String operation, int remainingQuota, String tip) {
    }

    /**
     * 获取当前时间(模型已实现)
     * 
     * @return
     */
    @GetMapping("/datetime")
    public String getDateTime() {
        return chatClient.prompt("What is the current date and time?")
                .tools(new DateTimeTools())
                .call()
                .content();
    }

    /**
     * 注解式工具：把带 @Tool 的普通 Java 对象传给 tools(...)。
     * 适合业务服务类方法少、依赖关系简单的场景。
     */
    @GetMapping("/weather")
    public String weather(@RequestParam(defaultValue = "杭州") String city) {
        return chatClient.prompt()
                .system("你可以调用工具获取天气。工具返回的是模拟数据，回答时说明这是 demo 数据。")
                .user("查询 " + city + " 今天的天气，并给出穿衣建议。")
                .tools(new WeatherTools())
                .call()
                .content();
    }

    /**
     * @ToolParam 技巧：给每个参数写清楚含义，模型更容易正确组装 tool arguments。
     */
    @GetMapping("/calculator")
    public String calculator(@RequestParam(defaultValue = "用 199 * 28 再加 36 算一个报价") String question) {
        return chatClient.prompt()
                .system("遇到数学计算必须调用 calculator 工具，不要心算。")
                .user(question)
                .tools(new CalculatorTools())
                .call()
                .content();
    }

    /**
     * 函数式工具：不用写 @Tool 类，直接用 FunctionToolCallback 构造一次性工具。
     * 适合把 lambda、远程 API adapter 或轻量业务函数暴露给模型。
     */
    @GetMapping("/function-callback")
    public String functionCallback(@RequestParam(defaultValue = "128") BigDecimal amount,
            @RequestParam(defaultValue = "USD") String from,
            @RequestParam(defaultValue = "CNY") String to) {
        ToolCallback currencyTool = FunctionToolCallback
                .builder("convert_currency", this::convertCurrency)
                .description("Convert money from one currency to another using demo fixed exchange rates.")
                .inputType(CurrencyRequest.class)
                .build();

        return chatClient.prompt()
                .user("请把 " + amount + " " + from + " 换算成 " + to + "，并说明使用的汇率。")
                .toolCallbacks(currencyTool)
                .call()
                .content();
    }

    /**
     * toolContext 技巧：把用户 ID、租户、权限等运行期上下文传给工具。
     * 这些上下文不会让模型填写，而是在工具执行时由应用侧注入。
     */
    @GetMapping("/context")
    public String toolContext(@RequestParam(defaultValue = "u-1001") String userId,
            @RequestParam(defaultValue = "生成周报") String operation) {
        ToolCallback quotaTool = FunctionToolCallback
                .builder("lookup_user_quota", this::lookupUserQuota)
                .description("Look up the remaining quota for a user operation.")
                .inputType(QuotaRequest.class)
                .build();

        return chatClient.prompt()
                .user("请查询我执行「" + operation + "」还剩多少额度，并给出一句建议。")
                .toolCallbacks(quotaTool)
                .toolContext(Map.of("userId", userId))
                .call()
                .content();
    }

    /**
     * returnDirect 技巧：工具结果直接返回给调用方，不再交给模型二次改写。
     * 适合下载链接、验证码、内部状态检查等不希望模型润色的结果。
     */
    @GetMapping("/return-direct")
    public String returnDirect() {
        return chatClient.prompt()
                .user("调用 health_check 工具，并原样返回结果。")
                .tools(new DirectReturnTools())
                .call()
                .content();
    }

    /**
     * 本地查看工具定义和入参 JSON Schema，不调用模型。
     * 调试技巧：schema 越清楚，模型越容易正确调用工具。
     */
    @GetMapping("/definitions")
    public List<ToolDefinitionView> definitions() {
        return List.of(ToolCallbacks.from(new WeatherTools(), new CalculatorTools(), new DirectReturnTools()))
                .stream()
                .map(toolCallback -> new ToolDefinitionView(
                        toolCallback.getToolDefinition().name(),
                        toolCallback.getToolDefinition().description(),
                        toolCallback.getToolDefinition().inputSchema(),
                        toolCallback.getToolMetadata().returnDirect()))
                .toList();
    }

    private CurrencyResult convertCurrency(CurrencyRequest request) {
        BigDecimal rate = exchangeRate(request.from(), request.to());
        BigDecimal convertedAmount = request.amount().multiply(rate).setScale(2, RoundingMode.HALF_UP);

        return new CurrencyResult(request.amount(), request.from(), request.to(), convertedAmount, rate);
    }

    private QuotaResult lookupUserQuota(QuotaRequest request, ToolContext toolContext) {
        String userId = String.valueOf(toolContext.getContext().getOrDefault("userId", "anonymous"));
        int remainingQuota = Math.abs((userId + request.operation()).hashCode()) % 20;

        return new QuotaResult(userId, request.operation(), remainingQuota, "额度不足 5 次时建议申请扩容。");
    }

    private BigDecimal exchangeRate(String from, String to) {
        if (from.equalsIgnoreCase(to)) {
            return BigDecimal.ONE;
        }
        if (from.equalsIgnoreCase("USD") && to.equalsIgnoreCase("CNY")) {
            return new BigDecimal("7.20");
        }
        if (from.equalsIgnoreCase("CNY") && to.equalsIgnoreCase("USD")) {
            return new BigDecimal("0.14");
        }
        if (from.equalsIgnoreCase("EUR") && to.equalsIgnoreCase("CNY")) {
            return new BigDecimal("7.85");
        }
        return BigDecimal.ONE;
    }

    static class WeatherTools {

        @Tool(name = "get_weather", description = "Get demo weather information for a city")
        public String getWeather(
                @ToolParam(description = "City name, for example Hangzhou or Beijing") String city) {
            return city + "：多云，22摄氏度，东北风 2 级。";
        }
    }

    static class CalculatorTools {

        @Tool(name = "calculator", description = "Calculate a math expression with add, subtract, multiply and divide")
        public BigDecimal calculate(
                @ToolParam(description = "Left operand") BigDecimal left,
                @ToolParam(description = "Operator. Allowed values: add, subtract, multiply, divide") String operator,
                @ToolParam(description = "Right operand") BigDecimal right) {
            return switch (operator) {
                case "add" -> left.add(right);
                case "subtract" -> left.subtract(right);
                case "multiply" -> left.multiply(right);
                case "divide" -> left.divide(right, 2, RoundingMode.HALF_UP);
                default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
            };
        }
    }

    static class DirectReturnTools {

        @Tool(name = "health_check", description = "Return the application health status", returnDirect = true)
        public String healthCheck() {
            return "UP";
        }
    }
}
