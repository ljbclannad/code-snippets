package com.lx.governance.user.repo;

import com.lx.governance.user.domain.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(long userId, String username, String passwordHash, String passwordSalt, String status) {
        return jdbcTemplate.update(
                "INSERT INTO t_user_account(user_id, username, password_hash, password_salt, status, created_at) VALUES (?,?,?,?,?,NOW())",
                userId, username, passwordHash, passwordSalt, status
        );
    }

    public UserAccount findByUsername(String username) {
        return jdbcTemplate.query(
                "SELECT user_id, username, password_hash, password_salt, status FROM t_user_account WHERE username=? LIMIT 1",
                (rs, n) -> {
                    UserAccount account = new UserAccount();
                    account.setUserId(rs.getLong("user_id"));
                    account.setUsername(rs.getString("username"));
                    account.setPasswordHash(rs.getString("password_hash"));
                    account.setPasswordSalt(rs.getString("password_salt"));
                    account.setStatus(rs.getString("status"));
                    return account;
                },
                username
        ).stream().findFirst().orElse(null);
    }

    public UserAccount findByUserId(long userId) {
        return jdbcTemplate.query(
                "SELECT user_id, username, password_hash, password_salt, status FROM t_user_account WHERE user_id=? LIMIT 1",
                (rs, n) -> {
                    UserAccount account = new UserAccount();
                    account.setUserId(rs.getLong("user_id"));
                    account.setUsername(rs.getString("username"));
                    account.setPasswordHash(rs.getString("password_hash"));
                    account.setPasswordSalt(rs.getString("password_salt"));
                    account.setStatus(rs.getString("status"));
                    return account;
                },
                userId
        ).stream().findFirst().orElse(null);
    }
}
