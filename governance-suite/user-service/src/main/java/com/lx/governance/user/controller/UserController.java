package com.lx.governance.user.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.auth.AuthTokenClaims;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.user.domain.LoginRequest;
import com.lx.governance.user.domain.LoginResponse;
import com.lx.governance.user.domain.RegisterUserRequest;
import com.lx.governance.user.domain.UserDetailResponse;
import com.lx.governance.user.domain.UserProfile;
import com.lx.governance.user.service.UserAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class UserController {
    private final UserAppService userAppService;

    public UserController(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    @PostMapping("/register")
    public ApiResponse<UserProfile> register(@RequestBody RegisterUserRequest request) {
        return ApiResponse.ok(userAppService.register(request), Trace.getOrCreate());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        return ApiResponse.ok(userAppService.login(request), Trace.getOrCreate());
    }

    @GetMapping("/me")
    public ApiResponse<UserDetailResponse> me(@RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId) {
        return ApiResponse.ok(userAppService.getMyDetail(requireUserId(userId)), Trace.getOrCreate());
    }

    @GetMapping("/auth/validate")
    public ApiResponse<AuthTokenClaims> validate(@RequestHeader(AuthHeaders.AUTHORIZATION) String authorization) {
        return ApiResponse.ok(userAppService.validateToken(authorization), Trace.getOrCreate());
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("missing authenticated user");
        }
        return userId;
    }
}
