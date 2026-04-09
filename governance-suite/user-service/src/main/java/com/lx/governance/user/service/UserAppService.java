package com.lx.governance.user.service;

import com.lx.governance.common.auth.AuthTokenClaims;
import com.lx.governance.common.auth.AuthTokenService;
import com.lx.governance.common.auth.PasswordCodec;
import com.lx.governance.common.id.SnowflakeIdGenerator;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.user.client.PaymentQueryClient;
import com.lx.governance.user.domain.LoginRequest;
import com.lx.governance.user.domain.LoginResponse;
import com.lx.governance.user.domain.RegisterUserRequest;
import com.lx.governance.user.domain.UserAccount;
import com.lx.governance.user.domain.UserDetailResponse;
import com.lx.governance.user.domain.UserPaymentDto;
import com.lx.governance.user.domain.UserProfile;
import com.lx.governance.user.repo.UserAccountRepository;
import com.lx.governance.user.repo.UserProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class UserAppService {
    private final UserAccountRepository userAccountRepository;
    private final UserProfileRepository userProfileRepository;
    private final PaymentQueryClient paymentQueryClient;
    private final AuthTokenService authTokenService;
    private final SnowflakeIdGenerator userIdGenerator;
    private final long tokenTtlSeconds;

    public UserAppService(UserAccountRepository userAccountRepository,
                          UserProfileRepository userProfileRepository,
                          PaymentQueryClient paymentQueryClient,
                          AuthTokenService authTokenService,
                          @Value("${id.snowflake.worker-id:3}") long workerId,
                          @Value("${id.snowflake.max-backward-ms:5}") long maxBackwardMs,
                          @Value("${app.auth.token-ttl-seconds:7200}") long tokenTtlSeconds) {
        this.userAccountRepository = userAccountRepository;
        this.userProfileRepository = userProfileRepository;
        this.paymentQueryClient = paymentQueryClient;
        this.authTokenService = authTokenService;
        this.userIdGenerator = new SnowflakeIdGenerator(workerId, maxBackwardMs);
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    @Transactional
    public UserProfile register(RegisterUserRequest request) {
        validateRegisterRequest(request);
        long userId = userIdGenerator.nextId();
        String salt = PasswordCodec.newSalt();
        String hash = PasswordCodec.hash(request.getPassword(), salt);
        String displayName = request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? request.getUsername()
                : request.getDisplayName().trim();
        try {
            userAccountRepository.insert(userId, request.getUsername().trim(), hash, salt, "ACTIVE");
            userProfileRepository.insert(userId, request.getUsername().trim(), displayName, "ACTIVE");
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("username already exists");
        }
        return userProfileRepository.findByUserId(userId);
    }

    public LoginResponse login(LoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank() || request.getPassword() == null) {
            throw new IllegalArgumentException("username and password are required");
        }
        UserAccount account = userAccountRepository.findByUsername(request.getUsername().trim());
        if (account == null) {
            throw new IllegalArgumentException("user not found");
        }
        if (!"ACTIVE".equalsIgnoreCase(account.getStatus())) {
            throw new IllegalArgumentException("user disabled");
        }
        String hash = PasswordCodec.hash(request.getPassword(), account.getPasswordSalt());
        if (!hash.equals(account.getPasswordHash())) {
            throw new IllegalArgumentException("invalid username or password");
        }
        UserProfile profile = userProfileRepository.findByUserId(account.getUserId());
        String token = authTokenService.issueToken(account.getUserId(), account.getUsername());
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setExpireAt(Instant.now().getEpochSecond() + tokenTtlSeconds);
        response.setUser(profile);
        return response;
    }

    public AuthTokenClaims validateToken(String authorization) {
        AuthTokenClaims claims = authTokenService.verify(authorization);
        UserAccount account = userAccountRepository.findByUserId(claims.getUserId());
        if (account == null || !"ACTIVE".equalsIgnoreCase(account.getStatus())) {
            throw new IllegalArgumentException("user not active");
        }
        return claims;
    }

    public UserDetailResponse getMyDetail(long userId) {
        UserProfile profile = userProfileRepository.findByUserId(userId);
        if (profile == null) {
            throw new IllegalArgumentException("user not found");
        }
        ApiResponse<List<UserPaymentDto>> paymentResponse = paymentQueryClient.listByUserId(userId);
        UserDetailResponse response = new UserDetailResponse();
        response.setProfile(profile);
        response.setPayments(paymentResponse == null || paymentResponse.getData() == null
                ? Collections.emptyList()
                : paymentResponse.getData());
        return response;
    }

    private void validateRegisterRequest(RegisterUserRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("password length must be at least 6");
        }
    }
}
