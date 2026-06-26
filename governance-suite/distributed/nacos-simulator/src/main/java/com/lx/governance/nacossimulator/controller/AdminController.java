package com.lx.governance.nacossimulator.controller;

import com.lx.governance.nacossimulator.dto.AdminStateResponse;
import com.lx.governance.nacossimulator.dto.PendingLongPollView;
import com.lx.governance.nacossimulator.model.ConfigItem;
import com.lx.governance.nacossimulator.model.ServiceInstanceRecord;
import com.lx.governance.nacossimulator.service.AdminStateService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

    private final AdminStateService adminStateService;

    public AdminController(AdminStateService adminStateService) {
        this.adminStateService = adminStateService;
    }

    @GetMapping("/api/admin/state")
    public AdminStateResponse state() {
        return adminStateService.getState();
    }

    @GetMapping("/api/admin/configs")
    public List<ConfigItem> configs() {
        return adminStateService.listConfigs();
    }

    @GetMapping("/api/admin/instances")
    public List<ServiceInstanceRecord> instances(@RequestParam(required = false) String serviceName) {
        return adminStateService.listInstances(serviceName);
    }

    @GetMapping("/api/admin/long-polls")
    public List<PendingLongPollView> longPolls() {
        return adminStateService.listLongPolls();
    }
}
