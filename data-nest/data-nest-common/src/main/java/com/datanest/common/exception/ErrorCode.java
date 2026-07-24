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

    // Data source errors (3xxx)
    DATASOURCE_NOT_FOUND(3001, "数据源不存在"),
    DATASOURCE_NAME_EXISTS(3002, "数据源名称已存在"),
    DATASOURCE_CONNECTION_FAILED(3003, "数据源连接失败"),
    DATASOURCE_UNSUPPORTED_TYPE(3004, "不支持的数据源类型"),
    HAS_REFERENCES(3005, "数据源被采集任务引用"),

    // Governance errors (4xxx)
    TASK_NOT_FOUND(4001, "采集任务不存在"),
    TASK_NAME_EXISTS(4002, "采集任务名称已存在"),
    TASK_ALREADY_RUNNING(4003, "采集任务正在执行中"),
    TASK_SCHEDULE_FAILED(4004, "任务调度失败"),
    HISTORY_NOT_FOUND(4005, "采集历史不存在"),
    METADATA_NOT_FOUND(4006, "元数据不存在"),

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
