package com.lx.governance.inventory.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.inventory.domain.DeductRequest;
import com.lx.governance.inventory.service.InventoryAppService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/")
public class InventoryController {

    private final InventoryAppService appService;

    public InventoryController(InventoryAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/deduct")
    public ApiResponse<String> deduct(@RequestBody DeductRequest req) {
        appService.deduct(req);
        return ApiResponse.ok("OK", Trace.getOrCreate());
    }
}
