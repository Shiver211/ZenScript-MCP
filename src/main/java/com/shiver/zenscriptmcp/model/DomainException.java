package com.shiver.zenscriptmcp.model;

/** 工具执行失败时返回的稳定领域错误。 */
public final class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
