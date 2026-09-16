package com.lx.governance.common.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class AuthTokenClaims {
    private long userId;
    private String username;
    private long expireAt;
    private Set<String> roles = Collections.emptySet();

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public long getExpireAt() { return expireAt; }
    public void setExpireAt(long expireAt) { this.expireAt = expireAt; }
    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) {
        this.roles = roles == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }
}
