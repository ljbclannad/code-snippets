package com.lx.governance.appointment.websocket;

import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.auth.AuthTokenClaims;
import com.lx.governance.common.auth.AuthTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class AppointmentHandshakeInterceptor implements HandshakeInterceptor {

    private final AuthTokenService authTokenService;

    public AppointmentHandshakeInterceptor(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @Override
    public boolean beforeHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull org.springframework.web.socket.WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes) {
        String rawToken = request.getHeaders().getFirst(AuthHeaders.AUTHORIZATION);
        if ((rawToken == null || rawToken.isBlank()) && request instanceof ServletServerHttpRequest servletRequest) {
            rawToken = servletRequest.getServletRequest().getParameter("token");
        }

        try {
            AuthTokenClaims claims = authTokenService.verify(rawToken);
            attributes.put("userId", claims.getUserId());
            attributes.put("username", claims.getUsername());
            return true;
        } catch (Exception e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull org.springframework.web.socket.WebSocketHandler wsHandler,
            Exception exception) {
    }
}
