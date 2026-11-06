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

    private static final String DEFAULT_TENANT_ID = "ws-90124-enterprise";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_OPERATOR = "ROLE_OPERATOR";

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
                "Lead Operator",
                ROLE_ADMIN);

        initializeUserIfAbsent(
                "analyst",
                "analyst@rollercoaster.com",
                "Analyst123!",
                "Data Analyst",
                ROLE_OPERATOR);
    }

    private void initializeUserIfAbsent(
            String username,
            String email,
            String rawPassword,
            String fullName,
            String role) {

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return;
        }

        String encodedPassword = passwordEncoder.encode(rawPassword);
        UserEntity newUser = UserEntity.create(
                username,
                email,
                encodedPassword,
                fullName,
                role,
                DEFAULT_TENANT_ID);

        userRepository.save(newUser);
        LOGGER.info("[Auth] Initialized default user account '{}' with role '{}'.", username, role);
    }
}
