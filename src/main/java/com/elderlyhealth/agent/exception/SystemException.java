package com.elderlyhealth.agent.exception;

public class SystemException extends BusinessException {

    public SystemException(String message) {
        super(ErrorCode.SYSTEM_ERROR, message);
    }

    public SystemException(String message, Throwable cause) {
        super(ErrorCode.SYSTEM_ERROR, message);
        initCause(cause);
    }
}
