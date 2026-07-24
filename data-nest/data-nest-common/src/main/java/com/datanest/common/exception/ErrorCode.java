package com.datanest.common.exception;

/**
 * Business error codes.
 */
public enum ErrorCode {

    // Auth errors (1xxx)
    USER_NOT_FOUND(1001, "用户不存在"),
    PASSWORD_ERROR(1002, "密码错误"),
    ACCOUNT_DISABLED(1003, "账号已禁用"),
    UNAUTHORIZED(1004, "未登录或 Token 已过期"),
    FORBIDDEN(1005, "无权限访问"),
    OLD_PASSWORD_ERROR(1006, "旧密码错误"),

    // User management errors (2xxx)
    USERNAME_EXISTS(2001, "用户名已存在"),
    INVALID_ROLE(2002, "无效的角色"),
    CANNOT_DISABLE_SELF(2003, "不能禁用自己的账号"),

    // System errors (9xxx)
    INTERNAL_ERROR(9999, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
