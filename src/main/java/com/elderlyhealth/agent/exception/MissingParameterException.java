package com.elderlyhealth.agent.exception;

public class MissingParameterException extends BusinessException {

    public MissingParameterException(String message) {
        super(ErrorCode.MISSING_PARAMETER, message);
    }
}
