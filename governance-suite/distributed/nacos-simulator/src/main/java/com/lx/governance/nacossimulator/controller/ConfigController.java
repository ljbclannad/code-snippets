package com.lx.governance.nacossimulator.controller;

import com.lx.governance.nacossimulator.dto.ConfigChangeResponse;
import com.lx.governance.nacossimulator.dto.ConfigListenerRequest;
import com.lx.governance.nacossimulator.dto.PublishConfigRequest;
import com.lx.governance.nacossimulator.model.ConfigItem;
import com.lx.governance.nacossimulator.service.ConfigService;
import com.lx.governance.nacossimulator.service.LongPollingService;
import jakarta.validation.Valid;
import java.util.concurrent.ExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ConfigController {

    private final ConfigService configService;
    private final LongPollingService longPollingService;

    public ConfigController(ConfigService configService, LongPollingService longPollingService) {
        this.configService = configService;
        this.longPollingService = longPollingService;
    }

    @PostMapping("/api/config/publish")
    public ConfigItem publish(@Valid @RequestBody PublishConfigRequest request) {
        return configService.publish(request.dataId(), request.group(), request.content());
    }

    @GetMapping("/api/config/get")
    public ConfigItem get(@RequestParam String dataId, @RequestParam String group) {
        return configService.get(dataId, group)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "config not found"));
    }

    @PostMapping("/api/config/listener")
    public ConfigChangeResponse listener(@Valid @RequestBody ConfigListenerRequest request)
            throws ExecutionException, InterruptedException {
        return longPollingService.listenAsync(request.clientId(), request.probes()).get();
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public String handleBadRequest(IllegalArgumentException ex) {
        return ex.getMessage();
    }
}
