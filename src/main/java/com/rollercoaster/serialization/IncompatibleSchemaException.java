package com.rollercoaster.serialization;

public class IncompatibleSchemaException extends RuntimeException {
    private final String archiveFormatVersion;
    private final String supportedEngineVersion;

    public IncompatibleSchemaException(String message, String archiveFormatVersion, String supportedEngineVersion) {
        super(message);
        this.archiveFormatVersion = archiveFormatVersion;
        this.supportedEngineVersion = supportedEngineVersion;
    }

    public String getArchiveFormatVersion() {
        return archiveFormatVersion;
    }

    public String getSupportedEngineVersion() {
        return supportedEngineVersion;
    }
}
