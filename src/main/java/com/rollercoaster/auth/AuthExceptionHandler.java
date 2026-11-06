package com.rollercoaster.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(basePackages = "com.rollercoaster.auth")
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuthException(AuthException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "error", exception.getMessage(),
                "message", exception.getMessage(),
                "status", exception.getStatus().value(),
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException exception) {
        String validationMessage = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed for request payload.");

        return ResponseEntity.badRequest().body(Map.of(
                "error", validationMessage,
                "message", validationMessage,
                "status", HttpStatus.BAD_REQUEST.value(),
                "timestamp", Instant.now().toString()));
    }
}
