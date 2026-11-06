package com.rollercoaster.serialization;

public class CorruptedPackageException extends RuntimeException {
    public CorruptedPackageException(String message) {
        super(message);
    }

    public CorruptedPackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
