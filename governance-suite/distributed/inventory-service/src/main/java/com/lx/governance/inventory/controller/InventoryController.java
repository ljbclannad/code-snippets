package com.lx.governance.inventory.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.inventory.domain.DeductRequest;
import com.lx.governance.inventory.service.InventoryAppService;
import com.lx.governance.inventory.service.InventoryTccAction;

@RestController
@RequestMapping("/")
public class InventoryController {

    private final InventoryAppService appService;
    private final InventoryTccAction inventoryTccAction;

    public InventoryController(InventoryAppService appService, InventoryTccAction inventoryTccAction) {
        this.appService = appService;
        this.inventoryTccAction = inventoryTccAction;
    }

    @PostMapping("/deduct")
    public ApiResponse<String> deduct(@RequestBody DeductRequest req) {
        appService.deduct(req);
        return ApiResponse.ok("OK", Trace.getOrCreate());
    }

    @PostMapping("/tcc/try")
    public ApiResponse<String> tryDeductTcc(@RequestBody DeductRequest req) {
        inventoryTccAction.prepare(null, req.getOrderNo(), req.getProductId(), req.getQuantity());
        return ApiResponse.ok("OK", Trace.getOrCreate());
    }
}
