package com.rollercoaster.config;

import com.rollercoaster.serialization.CorruptedPackageException;
import com.rollercoaster.serialization.IncompatibleSchemaException;
import com.rollercoaster.writeback.OptimisticLockingConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps domain and security exceptions to RFC 9457 ProblemDetail payloads keyed
 * by `errorCode`.
 */
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Insufficient Permissions");
        problem.setProperty("errorCode", "ERR_RBAC_FORBIDDEN");
        // Unique ID lets callers reference denied attempts in logs without exposing
        // RBAC policy details.
        problem.setProperty("correlationId", UUID.randomUUID().toString());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(CorruptedPackageException.class)
    public ProblemDetail handleCorruptedPackage(CorruptedPackageException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Corrupted Project Archive Checksum");
        problem.setProperty("errorCode", "ERR_CORRUPTED_PACKAGE");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(IncompatibleSchemaException.class)
    public ProblemDetail handleIncompatibleSchema(IncompatibleSchemaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Incompatible Project Schema Version");
        problem.setProperty("errorCode", "ERR_INCOMPATIBLE_SCHEMA");
        // Surface both versions so the client can prompt for migration or an engine
        // upgrade.
        problem.setProperty("archiveVersion", ex.getArchiveFormatVersion());
        problem.setProperty("supportedEngineVersion", ex.getSupportedEngineVersion());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingConflictException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Write-Back Optimistic Locking Conflict");
        problem.setProperty("errorCode", "ERR_OPTIMISTIC_LOCK");
        // Include both versions so the client can rebase or prompt for conflict
        // resolution.
        problem.setProperty("expectedVersion", ex.getExpectedVersion());
        problem.setProperty("actualVersion", ex.getActualVersion());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
        //
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Invalid request argument.";
        boolean syntaxError = detail.startsWith("ERR_RAX_SYNTAX");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle(syntaxError ? "RAX Syntax Validation Error" : "Invalid Request Argument");
        problem.setProperty("errorCode", syntaxError ? "ERR_RAX_SYNTAX" : "ERR_INVALID_ARGUMENT");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String validationMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getDefaultMessage())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("Validation failed for request payload.");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, validationMessage);
        problem.setTitle("Validation Error");
        problem.setProperty("errorCode", "ERR_VALIDATION_FAILED");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(NullPointerException.class)
    public ProblemDetail handleNullPointer(NullPointerException ex) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Required domain argument must not be null.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Missing Required Argument");
        problem.setProperty("errorCode", "ERR_MISSING_ARGUMENT");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        Throwable specificCause = ex.getMostSpecificCause();
        String detail = (specificCause != null && specificCause.getMessage() != null)
                ? specificCause.getMessage()
                : "Malformed request payload.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Malformed Request Payload");
        problem.setProperty("errorCode", "ERR_MALFORMED_REQUEST");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Invalid domain state.";
        boolean circularDep = detail.startsWith("ERR_CIRCULAR_DEP");
        HttpStatus status = circularDep ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(circularDep ? "Circular Dependency Detected" : "Domain State Error");
        problem.setProperty("errorCode", circularDep ? "ERR_CIRCULAR_DEP" : "ERR_ILLEGAL_STATE");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}

