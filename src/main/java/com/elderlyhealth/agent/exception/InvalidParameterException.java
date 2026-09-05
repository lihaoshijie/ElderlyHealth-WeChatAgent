package com.elderlyhealth.agent.exception;

public class InvalidParameterException extends BusinessException {

    public InvalidParameterException(String message) {
        super(ErrorCode.INVALID_PARAMETER, message);
    }
}
