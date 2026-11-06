package com.rollercoaster.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank(message = "Username is required.")
            @Size(min = 3, max = 64, message = "Username must be at least 3 characters.")
            String username,

            @NotBlank(message = "A valid email address is required.")
            @Email(message = "A valid email address is required.")
            String email,

            @NotBlank(message = "Password is required.")
            @Size(min = 6, max = 128, message = "Password must be at least 6 characters.")
            String password,

            String fullName,
            String role
    ) {}

    public record LoginRequest(
            @NotBlank(message = "Username and password are required.")
            String username,

            @NotBlank(message = "Username and password are required.")
            String password
    ) {}

    public record AuthResponse(
            String token,
            String tokenType,
            UserDto user,
            String message
    ) {
        public static final String BEARER_TOKEN_TYPE = "Bearer";

        public static AuthResponse of(String token, UserEntity user, String message) {
            Objects.requireNonNull(user, "User entity must not be null when constructing AuthResponse");
            return new AuthResponse(
                    token,
                    BEARER_TOKEN_TYPE,
                    UserDto.from(user),
                    message
            );
        }
    }

    public record UserDto(
            UUID id,
            String username,
            String email,
            String fullName,
            String role,
            String tenantId,
            Instant createdAt
    ) {
        public static UserDto from(UserEntity user) {
            if (user == null) {
                return null;
            }
            return new UserDto(
                    user.getId(),
                    user.getUsername(),
                    user.getEmail(),
                    user.getFullName(),
                    user.getRole(),
                    user.getTenantId(),
                    user.getCreatedAt()
            );
        }
    }
}
