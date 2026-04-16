package com.spring.simulation.demo;

import com.spring.simulation.ioc.Autowired;
import com.spring.simulation.ioc.Component;

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
