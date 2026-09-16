package com.lx.governance.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.common.auth.AuthTokenService;
import com.lx.governance.common.id.IdGenerator;
import com.lx.governance.user.client.PaymentQueryClient;
import com.lx.governance.user.domain.LoginRequest;
import com.lx.governance.user.domain.RegisterUserRequest;
import com.lx.governance.user.domain.UserAccount;
import com.lx.governance.user.repo.UserAccountRepository;
import com.lx.governance.user.repo.UserAdminRoleRepository;
import com.lx.governance.user.repo.UserProfileRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class UserAppServiceTest {
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private UserAdminRoleRepository userAdminRoleRepository;
    @Mock private PaymentQueryClient paymentQueryClient;
    @Mock private IdGenerator userIdSegmentGenerator;
    @Mock private IdGenerator userIdSnowflakeGenerator;

    @Test
    void loginSignsOnlyWhitelistedRolesReadFromRepository() {
        UserAccount account = activeAccount();
        when(userAccountRepository.findByUsername("finance")).thenReturn(account);
        when(userAdminRoleRepository.findRoleNamesByUserId(7L)).thenReturn(List.of("FINANCE_VIEWER", "UNKNOWN"));
        UserAppService service = service();
        LoginRequest request = new LoginRequest();
        request.setUsername("finance");
        request.setPassword("secret");

        String token = service.login(request).getToken();

        assertThat(new AuthTokenService("test-secret", 7200).verify(token).getRoles())
                .containsExactly("FINANCE_VIEWER");
    }

    @Test
    void registrationNeverReadsOrWritesAdminRoles() {
        when(userIdSegmentGenerator.nextId()).thenReturn(8L);
        UserAppService service = service();
        RegisterUserRequest request = new RegisterUserRequest();
        request.setUsername("customer");
        request.setPassword("secret");

        service.register(request);

        verify(userAdminRoleRepository, never()).findRoleNamesByUserId(anyLong());
    }

    private UserAppService service() {
        return new UserAppService(userAccountRepository, userProfileRepository, userAdminRoleRepository,
                paymentQueryClient, new AuthTokenService("test-secret", 7200), "segment",
                userIdSegmentGenerator, userIdSnowflakeGenerator, 7200, new SimpleMeterRegistry());
    }

    private UserAccount activeAccount() {
        UserAccount account = new UserAccount();
        account.setUserId(7L);
        account.setUsername("finance");
        account.setPasswordSalt("salt");
        account.setPasswordHash(com.lx.governance.common.auth.PasswordCodec.hash("secret", "salt"));
        account.setStatus("ACTIVE");
        return account;
    }
}
