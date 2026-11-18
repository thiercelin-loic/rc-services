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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request Argument");
        problem.setProperty("errorCode", "ERR_INVALID_ARGUMENT");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
