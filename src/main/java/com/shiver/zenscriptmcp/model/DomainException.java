package com.shiver.zenscriptmcp.model;

import com.google.gson.JsonObject;

/** 工具执行失败时返回的稳定领域错误。 */
public final class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String stage;
    private final JsonObject details;

    public DomainException(String code, String message) {
        this(code, message, "execution", new JsonObject());
    }

    public DomainException(String code, String message, String stage, JsonObject details) {
        super(message);
        this.code = code;
        this.stage = stage;
        this.details = Json.copy(details);
    }

    public String getCode() {
        return code;
    }

    public JsonObject toJson() {
        return Json.object("code", code, "message", getMessage(), "stage", stage, "details", Json.copy(details));
    }
}
