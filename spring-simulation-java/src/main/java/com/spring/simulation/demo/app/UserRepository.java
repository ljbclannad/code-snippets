package com.spring.simulation.demo.app;

public interface UserRepository {
    void save(String username);
    String findByName(String username);
}
