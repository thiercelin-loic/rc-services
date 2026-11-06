package com.rollercoaster.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_TENANT_ID = "ws-90124-enterprise";
    private static final String DEFAULT_ROLE = "ROLE_OPERATOR";
    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthServiceImpl(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            TokenBlacklistService tokenBlacklistService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    public AuthDtos.AuthResponse signup(AuthDtos.SignupRequest request) {
        validateSignupRequest(request);

        String normalizedUsername = request.username().trim().toLowerCase();
        String normalizedEmail = request.email().trim().toLowerCase();

        assertUsernameAvailable(normalizedUsername);
        assertEmailAvailable(normalizedEmail);

        // DEF-02: Public self-registration assigns strictly DEFAULT_ROLE to prevent
        // privilege escalation
        String assignedRole = DEFAULT_ROLE;
        String encodedPassword = passwordEncoder.encode(request.password());

        UserEntity newUser = UserEntity.create(
                normalizedUsername,
                normalizedEmail,
                encodedPassword,
                request.fullName(),
                assignedRole,
                DEFAULT_TENANT_ID);

        UserEntity savedUser = userRepository.save(newUser);
        String accessToken = jwtTokenProvider.generateToken(savedUser);

        return AuthDtos.AuthResponse.of(accessToken, savedUser, "Account created successfully.");
    }

    @Override
    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        validateLoginRequest(request);

        String normalizedIdentifier = request.username().trim().toLowerCase();
        Optional<UserEntity> userOptional = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(
                normalizedIdentifier,
                normalizedIdentifier);

        if (userOptional.isEmpty()) {
            throw new AuthException.InvalidCredentialsException("Invalid username or password.");
        }

        UserEntity user = userOptional.get();
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new AuthException.InvalidCredentialsException("Invalid username or password.");
        }

        if (!user.isActive()) {
            throw new AuthException.AccountDeactivatedException("Account has been deactivated.");
        }

        String accessToken = jwtTokenProvider.generateToken(user);
        return AuthDtos.AuthResponse.of(accessToken, user, "Signed in successfully.");
    }

    @Override
    @Transactional(readOnly = true)
    public AuthDtos.UserDto getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isAuthenticatedPrincipal(authentication)) {
            throw new AuthException.UnauthorizedException("Not authenticated");
        }

        String username = authentication.getName();
        UserEntity user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new AuthException.UserNotFoundException("User not found"));

        return AuthDtos.UserDto.from(user);
    }

    @Override
    public void logout() {
        SecurityContextHolder.clearContext();
    }

    @Override
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            java.time.Duration remainingTtl = jwtTokenProvider.getRemainingTtl(token);
            tokenBlacklistService.blacklistToken(token, remainingTtl);
        }
        SecurityContextHolder.clearContext();
    }

    private void validateSignupRequest(AuthDtos.SignupRequest request) {
        if (request == null) {
            throw new AuthException.ValidationException("Signup request body is required.");
        }
        if (request.username() == null || request.username().trim().length() < 3) {
            throw new AuthException.ValidationException("Username must be at least 3 characters.");
        }
        if (request.email() == null || !request.email().contains("@")) {
            throw new AuthException.ValidationException("A valid email address is required.");
        }
        if (request.password() == null || request.password().length() < 6) {
            throw new AuthException.ValidationException("Password must be at least 6 characters.");
        }
    }

    private void validateLoginRequest(AuthDtos.LoginRequest request) {
        if (request == null) {
            throw new AuthException.ValidationException("Login request body is required.");
        }
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new AuthException.ValidationException("Username and password are required.");
        }
    }

    private void assertUsernameAvailable(String username) {
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new AuthException.UserAlreadyExistsException("Username is already registered.");
        }
    }

    private void assertEmailAvailable(String email) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthException.UserAlreadyExistsException("Email address is already in use.");
        }
    }

    private boolean isAuthenticatedPrincipal(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !ANONYMOUS_PRINCIPAL.equals(authentication.getPrincipal());
    }
}
