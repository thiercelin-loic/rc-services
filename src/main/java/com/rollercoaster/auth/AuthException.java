package com.rollercoaster.auth;

import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

    private final HttpStatus status;

    public AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static class UserAlreadyExistsException extends AuthException {
        public UserAlreadyExistsException(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    public static class InvalidCredentialsException extends AuthException {
        public InvalidCredentialsException(String message) {
            super(HttpStatus.UNAUTHORIZED, message);
        }
    }

    public static class AccountDeactivatedException extends AuthException {
        public AccountDeactivatedException(String message) {
            super(HttpStatus.FORBIDDEN, message);
        }
    }

    public static class UserNotFoundException extends AuthException {
        public UserNotFoundException(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }

    public static class UnauthorizedException extends AuthException {
        public UnauthorizedException(String message) {
            super(HttpStatus.UNAUTHORIZED, message);
        }
    }

    public static class ValidationException extends AuthException {
        public ValidationException(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }
}
