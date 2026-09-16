package com.lx.governance.order.adminauth;

import java.util.Set;

public record VerifiedAdminIdentity(long userId, String username, Set<String> roles) { }
