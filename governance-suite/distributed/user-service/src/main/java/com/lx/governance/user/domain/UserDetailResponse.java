package com.lx.governance.user.domain;

import java.util.List;

public class UserDetailResponse {
    private UserProfile profile;
    private List<UserPaymentDto> payments;

    public UserProfile getProfile() {
        return profile;
    }

    public void setProfile(UserProfile profile) {
        this.profile = profile;
    }

    public List<UserPaymentDto> getPayments() {
        return payments;
    }

    public void setPayments(List<UserPaymentDto> payments) {
        this.payments = payments;
    }
}
