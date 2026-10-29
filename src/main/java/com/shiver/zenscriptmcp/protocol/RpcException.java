package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonObject;

/** 携带 HTTP 状态与 JSON-RPC 错误信息的协议异常。 */
public final class RpcException extends RuntimeException {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int HEADER_MISMATCH = -32020;
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    private static final long serialVersionUID = 1L;

    private final int code;
    private final int httpStatus;
    private final JsonObject data;

    public RpcException(int code, int httpStatus, String message) {
        this(code, httpStatus, message, null);
    }

    public RpcException(int code, int httpStatus, String message, JsonObject data) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public JsonObject getData() {
        return data;
    }
}
