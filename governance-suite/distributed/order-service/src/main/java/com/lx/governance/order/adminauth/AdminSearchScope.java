package com.lx.governance.order.adminauth;

import java.util.Set;

public record AdminSearchScope(long userId, String username, Set<String> roles) { }
