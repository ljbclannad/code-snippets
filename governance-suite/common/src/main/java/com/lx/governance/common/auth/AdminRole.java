package com.lx.governance.common.auth;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public enum AdminRole {
    ORDER_OPERATOR,
    FINANCE_VIEWER,
    INVENTORY_VIEWER;

    public static Set<String> normalize(Collection<String> roles) {
        if (roles == null) return Set.of();
        Set<AdminRole> accepted = EnumSet.noneOf(AdminRole.class);
        for (String role : roles) {
            if (role == null) continue;
            try { accepted.add(valueOf(role)); } catch (IllegalArgumentException ignored) { }
        }
        return accepted.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
