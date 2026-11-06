package com.rollercoaster.writeback;

public class OptimisticLockingConflictException extends RuntimeException {

    private final long expectedVersion;
    private final long actualVersion;

    public OptimisticLockingConflictException(String message, long expectedVersion, long actualVersion) {
        super(message);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
