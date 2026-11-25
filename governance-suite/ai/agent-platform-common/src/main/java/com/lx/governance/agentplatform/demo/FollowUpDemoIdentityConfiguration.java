package com.lx.governance.agentplatform.demo;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Set;

/** 仅为回环地址上的随访演示建立合成 Servlet 身份。 */
@Configuration(proxyBeanMethods = false)
@Profile("follow-up-demo & !prod & !production")
public class FollowUpDemoIdentityConfiguration {
    private static final String SESSION_KEY = FollowUpDemoIdentityConfiguration.class.getName();
    private static final String LOGIN_PATH = "/demo-login";
    private static final String LOGIN_FORM = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>随访演示登录</title>
            <style>
            body{margin:0;background:#f6f6fa;color:#1b1d32;font:16px system-ui,sans-serif}
            main{box-sizing:border-box;max-width:400px;margin:10vh auto;padding:26px;background:white;border:1px solid #e3e3ee;border-radius:14px}
            h1{margin:0 0 18px;font-size:24px}label{display:grid;gap:6px;margin:14px 0}
            input,button{box-sizing:border-box;padding:10px;font:inherit;border:1px solid #c9cadb;border-radius:7px}
            button{background:#5145ca;color:white;border:0;cursor:pointer}p{color:#653648}
            </style></head>
            <body><main><h1>%s演示登录</h1>%s
            <form method="post" action="/demo-login">
            <label>合成账号<input name="account" autocomplete="username" required></label>
            <label>密码<input name="password" type="password" autocomplete="current-password" required></label>
            <button type="submit">登录</button></form></main></body></html>
            """;

    @Bean
    Filter followUpDemoIdentity(
            @Value("${server.address:}") String address, @Value("${follow-up-demo.role}") String role,
            @Value("${follow-up-demo.account}") String account, @Value("${follow-up-demo.password}") String password) {
        if (!"127.0.0.1".equals(address) || !Set.of("PATIENT", "DOCTOR").contains(role)
                || !account.matches("demo-[a-z0-9-]{1,48}") || password.length() < 12) {
            throw new IllegalArgumentException("随访演示仅允许回环地址、合成账号和至少 12 字符的密码");
        }

        Principal principal = () -> account;
        return (rawRequest, rawResponse, chain) -> {
            HttpServletRequest request = (HttpServletRequest) rawRequest;
            HttpServletResponse response = (HttpServletResponse) rawResponse;
            if (!Set.of("127.0.0.1", "localhost").contains(request.getServerName())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            if (LOGIN_PATH.equals(request.getServletPath()) || LOGIN_PATH.equals(request.getRequestURI())) {
                login(request, response, role, account, password);
                return;
            }
            var session = request.getSession(false);
            if (request.getUserPrincipal() == null && session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_KEY))) {
                if (!sameOrigin(request)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
                response.setHeader("Cache-Control", "no-store");
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override public Principal getUserPrincipal() { return principal; }
                    @Override public String getRemoteUser() { return account; }
                    @Override public boolean isUserInRole(String requestedRole) { return role.equals(requestedRole); }
                }, response);
                return;
            }
            chain.doFilter(request, response);
        };
    }

    private static void login(HttpServletRequest request, HttpServletResponse response, String role, String account, String password)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String error = "";
        if ("POST".equals(request.getMethod())) {
            if (!sameOrigin(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            String supplied = request.getParameter("password");
            if (account.equals(request.getParameter("account")) && supplied != null && MessageDigest.isEqual(
                    password.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                var oldSession = request.getSession(false);
                if (oldSession != null) {
                    oldSession.invalidate();
                }
                request.getSession().setAttribute(SESSION_KEY, true);
                response.setStatus(HttpServletResponse.SC_SEE_OTHER);
                response.setHeader("Location", "DOCTOR".equals(role) ? "/doctor.html" : "/");
                return;
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            error = "<p role=\"alert\">账号或密码有误。</p>";
        } else if (!"GET".equals(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(LOGIN_FORM.formatted("DOCTOR".equals(role) ? "医生" : "患者", error));
    }

    private static boolean sameOrigin(HttpServletRequest request) {
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            return true;
        }
        String origin = request.getHeader("Origin");
        return !"cross-site".equals(request.getHeader("Sec-Fetch-Site")) && (origin == null
                || origin.equals(request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort()));
    }
}
