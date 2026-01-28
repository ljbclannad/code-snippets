package com.example.idgendemo.web;

import com.example.idgendemo.id.IdGenProperties;
import com.example.idgendemo.id.SwitchableIdGenerator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/idgen")
@Validated
public class IdGenAdminController {

    private final SwitchableIdGenerator sw;

    public IdGenAdminController(SwitchableIdGenerator sw) {
        this.sw = sw;
    }

    public static class UpdateRequest {
        @NotNull
        public IdGenProperties.Strategy strategy;

        public Boolean grayEnabled;

        @Min(0) @Max(100)
        public Integer snowflakePercent;
    }

    @PutMapping("/strategy")
    public Map<String, Object> update(@RequestBody @Validated UpdateRequest req) {
        SwitchableIdGenerator.StrategyConfig current = sw.getConfig();

        boolean gray = req.grayEnabled != null ? req.grayEnabled : current.grayEnabled;
        int percent = req.snowflakePercent != null ? req.snowflakePercent : current.snowflakePercent;

        SwitchableIdGenerator.StrategyConfig next = new SwitchableIdGenerator.StrategyConfig(
                req.strategy, gray, percent
        );
        sw.update(next);

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("strategyName", sw.strategyName());
        resp.put("config", Map.of(
                "strategy", next.strategy.name(),
                "grayEnabled", next.grayEnabled,
                "snowflakePercent", next.snowflakePercent
        ));
        return resp;
    }

    @GetMapping("/strategy")
    public Map<String, Object> current() {
        SwitchableIdGenerator.StrategyConfig c = sw.getConfig();
        return Map.of(
                "strategyName", sw.strategyName(),
                "strategy", c.strategy.name(),
                "grayEnabled", c.grayEnabled,
                "snowflakePercent", c.snowflakePercent
        );
    }
}
