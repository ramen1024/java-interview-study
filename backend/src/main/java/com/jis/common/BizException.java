package com.jis.common;

import lombok.Getter;

/**
 * 业务异常。由 {@link GlobalExceptionHandler} 统一转成响应体，
 * 调用方只需抛出，不必关心返回结构。
 */
@Getter
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
