package com.rollercoaster.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AuthDataInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthDataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthDataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments applicationArguments) {
        initializeDefaultAccounts();
    }

    private void initializeDefaultAccounts() {
        initializeUserIfAbsent(
                "operator",
                "operator@rollercoaster.com",
                "Operator123!",
                "Lead Operator");

        initializeUserIfAbsent(
                "analyst",
                "analyst@rollercoaster.com",
                "Analyst123!",
                "Data Analyst");
    }

    private void initializeUserIfAbsent(
            String username,
            String email,
            String rawPassword,
            String fullName) {

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return;
        }

        String encodedPassword = passwordEncoder.encode(rawPassword);
        UserEntity newUser = UserEntity.create(
                username,
                email,
                encodedPassword,
                fullName);

        userRepository.save(newUser);
        LOGGER.info("[Auth] Initialized default user account '{}'.", username);
    }
}
