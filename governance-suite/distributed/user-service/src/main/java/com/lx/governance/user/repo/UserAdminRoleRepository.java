package com.lx.governance.user.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.lx.governance.common.auth.AdminRole;

@Repository
public class UserAdminRoleRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserAdminRoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findRoleNamesByUserId(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT role FROM t_user_admin_role WHERE user_id = ? ORDER BY role", String.class, userId)
                .stream()
                .filter(role -> !AdminRole.normalize(List.of(role)).isEmpty())
                .toList();
    }
}
