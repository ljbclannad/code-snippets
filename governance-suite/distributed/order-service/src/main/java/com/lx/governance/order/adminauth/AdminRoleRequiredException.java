package com.lx.governance.order.adminauth;

public class AdminRoleRequiredException extends RuntimeException {
    public AdminRoleRequiredException() { super("an administrator role is required"); }
}
