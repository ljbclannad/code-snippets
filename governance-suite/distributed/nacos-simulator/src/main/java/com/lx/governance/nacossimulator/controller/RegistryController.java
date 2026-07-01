package com.lx.governance.nacossimulator.controller;

import com.lx.governance.nacossimulator.dto.DeregisterRequest;
import com.lx.governance.nacossimulator.dto.HeartbeatRequest;
import com.lx.governance.nacossimulator.dto.RegisterInstanceRequest;
import com.lx.governance.nacossimulator.model.ServiceInstanceRecord;
import com.lx.governance.nacossimulator.service.RegistryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegistryController {

    private final RegistryService registryService;

    public RegistryController(RegistryService registryService) {
        this.registryService = registryService;
    }

    @PostMapping("/api/registry/register")
    public ServiceInstanceRecord register(@Valid @RequestBody RegisterInstanceRequest request) {
        return registryService.register(
                request.serviceName(),
                request.ip(),
                request.port(),
                request.metadata() == null ? Map.of() : request.metadata(),
                request.ttlMillis());
    }

    @PostMapping("/api/registry/heartbeat")
    public ServiceInstanceRecord heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        return registryService.heartbeat(request.serviceName(), request.instanceId());
    }

    @PostMapping("/api/registry/deregister")
    public Map<String, Boolean> deregister(@Valid @RequestBody DeregisterRequest request) {
        registryService.deregister(request.serviceName(), request.instanceId());
        return Map.of("success", true);
    }

    @GetMapping("/api/registry/instances")
    public List<ServiceInstanceRecord> list(@RequestParam String serviceName) {
        return registryService.listHealthyInstances(serviceName);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public String handleBadRequest(IllegalArgumentException ex) {
        return ex.getMessage();
    }
}
