package com.datanest.common.exception;

/**
 * Business error codes.
 */
public enum ErrorCode {

    // Auth errors (1xxx)
    USER_NOT_FOUND(1001, "用户名或密码错误"),
    PASSWORD_ERROR(1002, "用户名或密码错误"),
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
    HAS_REFERENCES(3005, "数据源已被其他任务引用"),

    // Governance errors (4xxx)
    TASK_NOT_FOUND(4001, "采集任务不存在"),
    TASK_NAME_EXISTS(4002, "采集任务名称已存在"),
    TASK_ALREADY_RUNNING(4003, "采集任务正在执行中"),
    TASK_SCHEDULE_FAILED(4004, "任务调度失败"),
    HISTORY_NOT_FOUND(4005, "采集历史不存在"),
    METADATA_NOT_FOUND(4006, "元数据不存在"),

    // Data standard errors (5xxx)
    NAMING_STANDARD_NOT_FOUND(5001, "命名规范不存在"),
    NAMING_STANDARD_NAME_EXISTS(5002, "命名规范名称已存在"),
    FIELD_TYPE_STANDARD_NOT_FOUND(5003, "字段类型标准不存在"),
    FIELD_TYPE_STANDARD_NAME_EXISTS(5004, "字段类型标准名称已存在"),

    COMPLIANCE_CHECK_ITEM_REQUIRED(5005, "检查项目不能全部关闭"),
    INVALID_NAMING_STANDARD_PARAM(5006, "命名规范参数非法"),

    // Batch sync errors (6xxx)
    SYNC_JOB_NAME_EXISTS(6001, "同步任务名称已存在"),
    SYNC_JOB_NOT_FOUND(6002, "同步任务不存在"),
    ADDAX_EXECUTION_FAILED(6003, "Addax 执行失败"),
    TARGET_DATASOURCE_NOT_FOUND(6004, "目标数据源不存在"),
    SYNC_JOB_ALREADY_RUNNING(6005, "同步任务正在执行中"),

    // DAG / Data development errors (7xxx) — Sprint 3
    DAG_NOT_FOUND(7001, "DAG 不存在"),
    DAG_NAME_EXISTS(7002, "DAG 名称在项目内已存在"),
    DAG_CYCLE_DETECTED(7003, "DAG 存在循环依赖"),
    DAG_ISOLATED_NODE(7004, "DAG 存在孤立节点（不与任何执行起点连通）"),
    DAG_ALREADY_RUNNING(7005, "DAG 正在执行中"),
    DAG_DISABLED(7006, "DAG 已停用，无法执行"),
    NO_RUNNING_EXECUTION(7007, "当前 DAG 没有运行中的执行实例"),
    DAG_NODE_EXECUTE_FAILED(7008, "DAG 节点执行失败"),
    DAG_REFERENCED(7009, "同步任务已被 DAG 引用，无法删除"),
    PROJECT_NAME_EXISTS(7010, "项目名称已存在"),
    SQL_EXECUTE_FAILED(7011, "SQL 执行失败"),
    SQL_PARSE_FAILED(7012, "SQL 解析失败"),
    DS_API_ERROR(7013, "DolphinScheduler API 调用失败"),
    METADATA_REGISTRATION_FAILED(7014, "元数据注册失败"),
    SQL_PREVIEW_FAILED(7015, "SQL 预览执行失败"),
    DAG_EXECUTION_NOT_FOUND(7016, "DAG 执行实例不存在"),
    DAG_RERUN_ALREADY_RUNNING(7017, "重跑失败节点时检测到 DAG 正在执行中"),
    DAG_PROJECT_ID_REQUIRED(7018, "DAG 所属项目 ID 不能为空"),
    DAG_NAME_REQUIRED(7019, "DAG 名称不能为空"),
    DAG_VERSION_NOT_FOUND(7020, "DAG 版本不存在"),

    // DAG / Data development errors — Sprint 5 控制流增强
    SUB_DAG_CYCLE_DETECTED(7101, "子 DAG 存在循环引用，无法保存"),
    SUB_DAG_NOT_FOUND(7102, "引用的子 DAG 不存在"),
    SUB_DAG_DISABLED(7103, "引用的子 DAG 未启用"),
    CONDITION_CONFIG_INVALID(7104, "条件分支节点配置非法"),
    SUB_DAG_PROJECT_MISMATCH(7105, "子 DAG 必须与父 DAG 属于同一项目"),

    // Alert errors — Sprint 5 告警中心
    ALERT_RULE_NOT_FOUND(7201, "告警规则不存在"),
    ALERT_RULE_OBJECT_INVALID(7202, "告警规则对象类型非法"),

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
