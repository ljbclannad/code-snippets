package com.spring.simulation.demo;

public interface UserRepository {
    void save(String username);
    String findByName(String username);
}
