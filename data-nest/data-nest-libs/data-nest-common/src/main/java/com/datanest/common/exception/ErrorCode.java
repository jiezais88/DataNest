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
    HISTORY_TIME_RANGE_INVALID(4006, "执行历史时间范围非法"),
    METADATA_NOT_FOUND(4006, "元数据不存在"),

    // Asset catalog errors — Sprint 7 数据资产目录
    CLASSIFICATION_NOT_FOUND(4007, "分类不存在"),
    CLASSIFICATION_NAME_EXISTS(4008, "同级分类名称已存在"),
    CLASSIFICATION_IN_USE(4009, "分类仍被元数据表引用，请先解除分配"),
    CLASSIFICATION_PARENT_INVALID(4010, "父分类非法（主题必须挂在数据域下）"),

    // Asset collaboration errors — Sprint 8 资产目录深化（DC-06~09）
    // 注：4021 为技术文档 §9.1 规划码，当前删标签绑定按幂等设计不抛错，预留待单标签查询类端点使用
    ASSET_TAG_NOT_FOUND(4021, "标签不存在"),
    ASSET_COMMENT_NOT_FOUND(4022, "评论不存在"),
    ASSET_COMMENT_DELETE_FORBIDDEN(4023, "无权限删除他人评论"),
    ASSET_COLLABORATION_INVALID(4024, "资产协作数据校验失败"),

    // Data standard errors (5xxx)
    NAMING_STANDARD_NOT_FOUND(5001, "命名规范不存在"),
    NAMING_STANDARD_NAME_EXISTS(5002, "命名规范名称已存在"),
    FIELD_TYPE_STANDARD_NOT_FOUND(5003, "字段类型标准不存在"),
    FIELD_TYPE_STANDARD_NAME_EXISTS(5004, "字段类型标准名称已存在"),

    COMPLIANCE_CHECK_ITEM_REQUIRED(5005, "检查项目不能全部关闭"),
    INVALID_NAMING_STANDARD_PARAM(5006, "命名规范参数非法"),
    COMPLIANCE_CHECK_RESULT_NOT_FOUND(5007, "合规检查结果不存在"),

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
    SCHEDULER_API_ERROR(7013, "调度引擎 API 调用失败"),
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
    SUB_DAG_PARAM_INVALID(7106, "子 DAG 参数映射配置非法"),

    // Alert errors — Sprint 5 告警中心
    ALERT_RULE_NOT_FOUND(7201, "告警规则不存在"),
    ALERT_RULE_OBJECT_INVALID(7202, "告警规则对象类型非法"),
    ALERT_HISTORY_TIME_RANGE_INVALID(7203, "告警时间范围非法"),

    // Task template errors — Sprint 7 任务模板库（DD-09）
    TASK_TEMPLATE_NOT_FOUND(7301, "任务模板不存在"),
    TASK_TEMPLATE_NAME_EXISTS(7302, "任务模板名称已存在"),
    TASK_TEMPLATE_TYPE_INVALID(7303, "任务模板类型非法"),
    TASK_TEMPLATE_BUILTIN_READONLY(7304, "内置模板禁止修改或删除"),
    TASK_TEMPLATE_PLACEHOLDER_MISSING(7305, "必填占位符未填充"),
    TASK_TEMPLATE_CONFIG_INVALID(7306, "任务模板配置非法"),
    TASK_TEMPLATE_CREATE_FAILED(7307, "从模板创建任务失败"),

    // Quality errors — Sprint 6 数据质量（规则模板库）
    QUALITY_TEMPLATE_NOT_FOUND(4201, "质量规则模板不存在"),
    QUALITY_TEMPLATE_NAME_EXISTS(4202, "质量规则模板名称已存在"),
    QUALITY_TEMPLATE_TYPE_INVALID(4203, "质量规则模板类型非法"),
    QUALITY_TEMPLATE_BUILTIN_NOT_DELETE(4204, "内置模板不可删除"),

    // Quality errors — Sprint 6 数据质量（质量任务 + 质量规则配置层）
    QUALITY_JOB_NOT_FOUND(4205, "质量任务不存在"),
    QUALITY_JOB_NAME_EXISTS(4206, "质量任务名称已存在"),
    QUALITY_JOB_ALERT_LEVEL_INVALID(4207, "告警等级非法"),
    QUALITY_RULE_NOT_FOUND(4208, "质量规则不存在"),
    QUALITY_RULE_NAME_EXISTS(4209, "质量规则名称已存在"),
    QUALITY_RULE_EXECUTE_NOT_IMPLEMENTED(4210, "执行功能待实现"),
    QUALITY_RULE_BATCH_TEMPLATE_INVALID(4211, "模板批量应用参数非法"),
    QUALITY_TABLE_NOT_FOUND(4212, "目标表不存在"),
    QUALITY_JOB_CRON_REQUIRED(4213, "未配置 Cron 表达式"),

    // Quality errors — Sprint 8 数据质量（执行 + 结果记录）
    QUALITY_CHECK_BATCH_NOT_FOUND(4214, "质量检查批次不存在"),
    QUALITY_CHECK_SQL_GENERATE_FAILED(4215, "质量规则校验 SQL 生成失败"),
    QUALITY_CHECK_EXECUTE_FAILED(4216, "质量检查执行失败"),
    QUALITY_SCORE_CONFIG_INVALID(4217, "质量评分全局配置参数非法"),
    QUALITY_JOB_ALREADY_RUNNING(4218, "质量任务正在执行中"),
    QUALITY_CHECK_TIME_RANGE_INVALID(4219, "质量检查时间范围非法"),

    // Quality report errors — Sprint 8 F3 质量报告（DG-07）
    QUALITY_REPORT_PARAM_INVALID(4221, "报告参数非法"),
    QUALITY_REPORT_EXPORT_FAILED(4222, "报告导出失败"),

    // CDC pipeline errors — Sprint 8 实时 CDC 管道（8xxx）
    CDC_PIPELINE_CONFIG_INVALID(8000, "管道配置非法"),
    CDC_PIPELINE_NOT_FOUND(8001, "管道不存在"),
    CDC_PIPELINE_NAME_EXISTS(8002, "管道名称已存在"),
    CDC_PIPELINE_STATUS_INVALID(8003, "管道状态非法，请先停止"),
    CDC_SOURCE_CONNECTION_FAILED(8004, "源数据源连接失败"),
    CDC_SOURCE_BINLOG_DISABLED(8005, "源库 binlog 未开启或非 ROW 模式"),
    CDC_TARGET_WRITE_FAILED(8006, "目标湖仓写入失败"),
    CDC_PIPELINE_START_FAILED(8007, "管道启动失败"),
    CDC_PIPELINE_STOP_FAILED(8008, "管道停止失败"),
    CDC_DATASOURCE_REFERENCED(8009, "数据源已被 CDC 管道引用，请先删除管道"),
    CDC_SAVEPOINT_TRIGGER_FAILED(8010, "savepoint 触发失败或超时"),
    CDC_PIPELINE_NOT_RUNNING(8011, "管道未在运行中，无法执行该操作"),

    // Data Service errors — Sprint 10 数据服务（9xxx，对齐技术文档 §9.1）
    SQL_NOT_READ_ONLY(9001, "SQL 非只读语句，禁止执行"),
    SQL_SYNTAX_ERROR(9002, "SQL 语法错误"),
    SQL_TIMEOUT(9003, "查询超时中断"),
    TABLE_SENSITIVE(9004, "表为机密/内部敏感级，禁止操作"),
    API_KEY_INVALID(9005, "API Key 无效/禁用/未绑定"),
    API_RATE_LIMITED(9006, "请求超限"),
    API_NOT_PUBLISHED(9007, "API 未发布或已下线"),
    API_NOT_FOUND(9008, "API 不存在"),
    API_KEY_NAME_EXISTS(9009, "Key 名称已存在"),
    API_PATH_EXISTS(9010, "API 路径已存在"),
    API_EXEMPT_NOT_ALLOWED(9011, "机密表不可开白 / 非超管不可开白"),
    SENSITIVITY_SERVICE_UNAVAILABLE(9012, "分级服务暂不可用，请稍后重试"),

    // System errors (9xxx)
    NOT_FOUND(404, "请求的资源不存在"),
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
