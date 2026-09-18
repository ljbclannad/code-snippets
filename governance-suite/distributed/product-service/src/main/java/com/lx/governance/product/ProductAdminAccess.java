package com.lx.governance.product;

import com.lx.governance.common.auth.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProductAdminAccess implements HandlerInterceptor, WebMvcConfigurer {
    private final AuthTokenService tokens;
    private final Set<Long> administrators;

    public ProductAdminAccess(AuthTokenService tokens,
            @Value("${app.product.admin-user-ids:}") String administratorIds) {
        this.tokens = tokens;
        administrators = Arrays.stream(administratorIds.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        final long userId;
        try {
            userId = tokens.verify(request.getHeader("Authorization")).getUserId();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要有效登录令牌");
        }
        if (!administrators.contains(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有商品维护权限");
        }
        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/admin/products", "/admin/products/**");
    }
}
