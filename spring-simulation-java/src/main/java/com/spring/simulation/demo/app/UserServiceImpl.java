package com.spring.simulation.demo.app;

import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.annotation.Component;

@Component
public class UserServiceImpl implements UserService {
    @Autowired
    private UserRepository userRepository;

    @Override
    public void createUser(String username) {
        System.out.println("    [UserService] Creating user: " + username);
        userRepository.save(username);
    }

    @Override
    public String getUserInfo(String username) {
        System.out.println("    [UserService] Getting user info: " + username);
        return userRepository.findByName(username);
    }
}
