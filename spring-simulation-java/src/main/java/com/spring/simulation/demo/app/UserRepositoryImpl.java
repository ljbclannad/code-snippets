package com.spring.simulation.demo.app;

import com.spring.simulation.ioc.annotation.Component;
import java.util.HashMap;
import java.util.Map;

@Component
public class UserRepositoryImpl implements UserRepository {
    private Map<String, String> database = new HashMap<>();

    @Override
    public void save(String username) {
        database.put(username, "User data for " + username);
        System.out.println("        [UserRepository] Saved to database: " + username);
    }

    @Override
    public String findByName(String username) {
        String data = database.get(username);
        System.out.println("        [UserRepository] Retrieved from database: " + data);
        return data;
    }
}
