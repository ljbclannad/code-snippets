package com.lx.governance.user.repo;

import com.lx.governance.user.domain.UserProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserProfileRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(long userId, String username, String displayName, String status) {
        return jdbcTemplate.update(
                "INSERT INTO t_user(user_id, username, display_name, status, created_at) VALUES (?,?,?,?,NOW())",
                userId, username, displayName, status
        );
    }

    public UserProfile findByUserId(long userId) {
        return jdbcTemplate.query(
                "SELECT user_id, username, display_name, status, created_at FROM t_user WHERE user_id=? LIMIT 1",
                (rs, n) -> {
                    UserProfile profile = new UserProfile();
                    profile.setUserId(rs.getLong("user_id"));
                    profile.setUsername(rs.getString("username"));
                    profile.setDisplayName(rs.getString("display_name"));
                    profile.setStatus(rs.getString("status"));
                    profile.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    return profile;
                },
                userId
        ).stream().findFirst().orElse(null);
    }
}
