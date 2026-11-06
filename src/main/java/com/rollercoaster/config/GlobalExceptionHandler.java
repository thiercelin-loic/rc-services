package com.rollercoaster.config;

import com.rollercoaster.serialization.CorruptedPackageException;
import com.rollercoaster.serialization.IncompatibleSchemaException;
import com.rollercoaster.writeback.OptimisticLockingConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * RFC 9457 ProblemDetail exception translator with deep-links to documentation
 * diagnostic guides hosted at https://rollercoaster.com/docs/errors/{error-code}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String DOCS_ERROR_BASE = "https://rollercoaster.com/docs/errors/";

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Zero-Trust RBAC Authority Denied");
        problem.setType(URI.create(DOCS_ERROR_BASE + "err-rbac-forbidden"));
        problem.setProperty("errorCode", "ERR_RBAC_FORBIDDEN");
        problem.setProperty("correlationId", UUID.randomUUID().toString());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(CorruptedPackageException.class)
    public ProblemDetail handleCorruptedPackage(CorruptedPackageException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Corrupted Project Archive Checksum");
        problem.setType(URI.create(DOCS_ERROR_BASE + "err-corrupted-package"));
        problem.setProperty("errorCode", "ERR_CORRUPTED_PACKAGE");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(IncompatibleSchemaException.class)
    public ProblemDetail handleIncompatibleSchema(IncompatibleSchemaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Incompatible Project Schema Version");
        problem.setType(URI.create(DOCS_ERROR_BASE + "err-incompatible-schema"));
        problem.setProperty("errorCode", "ERR_INCOMPATIBLE_SCHEMA");
        problem.setProperty("archiveVersion", ex.getArchiveFormatVersion());
        problem.setProperty("supportedEngineVersion", ex.getSupportedEngineVersion());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingConflictException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Write-Back Optimistic Locking Conflict");
        problem.setType(URI.create(DOCS_ERROR_BASE + "err-optimistic-lock"));
        problem.setProperty("errorCode", "ERR_OPTIMISTIC_LOCK");
        problem.setProperty("expectedVersion", ex.getExpectedVersion());
        problem.setProperty("actualVersion", ex.getActualVersion());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request Argument");
        problem.setType(URI.create(DOCS_ERROR_BASE + "err-invalid-argument"));
        problem.setProperty("errorCode", "ERR_INVALID_ARGUMENT");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
