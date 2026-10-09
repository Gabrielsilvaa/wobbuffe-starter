package com.wobbuffe.consumer;

public class PermanentOperationException extends RuntimeException {
    private final String code;

    public PermanentOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PermanentOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
