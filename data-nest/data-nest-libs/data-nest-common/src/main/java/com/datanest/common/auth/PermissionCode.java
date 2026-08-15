package com.datanest.common.auth;

/**
 * 按钮级权限点 code 常量（Sprint 11 F2，技术文档 D-1 / §6）。
 * <p>
 * 权限点 code 规范：{@code 模块:动作}。本类作为全服务 {@code @SaCheckPermission} 注解的唯一常量来源，
 * 禁止在 Controller 里手写权限点字符串。name（中文 label）落库于 system 的 Flyway 种子脚本
 * {@code V1.1.1__sprint11_rbac.sql}，前端勾选树/菜单显隐复用同一套 code。
 * <p>
 * 预置角色分配以 PRD §6.2.1 按钮级矩阵为基准（见种子脚本角色关联段）。
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    // ============ 数据源管理 ============
    public static final String DATASOURCE_VIEW = "datasource:view";
    public static final String DATASOURCE_CREATE = "datasource:create";
    public static final String DATASOURCE_UPDATE = "datasource:update";
    public static final String DATASOURCE_DELETE = "datasource:delete";
    public static final String DATASOURCE_TEST = "datasource:test";

    // ============ 批量同步任务 ============
    public static final String SYNC_VIEW = "sync:view";
    public static final String SYNC_CREATE = "sync:create";
    public static final String SYNC_UPDATE = "sync:update";
    public static final String SYNC_DELETE = "sync:delete";
    public static final String SYNC_EXECUTE = "sync:execute";
    public static final String SYNC_HISTORY = "sync:history";

    // ============ CDC 管道 ============
    public static final String CDC_VIEW = "cdc:view";
    public static final String CDC_CREATE = "cdc:create";
    public static final String CDC_UPDATE = "cdc:update";
    public static final String CDC_DELETE = "cdc:delete";
    public static final String CDC_EXECUTE = "cdc:execute";
    public static final String CDC_MONITOR = "cdc:monitor";

    // ============ DAG 编排 ============
    public static final String DAG_VIEW = "dag:view";
    public static final String DAG_CREATE = "dag:create";
    public static final String DAG_UPDATE = "dag:update";
    public static final String DAG_DELETE = "dag:delete";
    public static final String DAG_EXECUTE = "dag:execute";
    public static final String DAG_HISTORY = "dag:history";

    // ============ 任务模板库 ============
    public static final String TEMPLATE_VIEW = "template:view";
    public static final String TEMPLATE_CREATE = "template:create";
    public static final String TEMPLATE_UPDATE = "template:update";
    public static final String TEMPLATE_DELETE = "template:delete";

    // ============ 元数据 ============
    public static final String METADATA_VIEW = "metadata:view";
    public static final String METADATA_COMMENT = "metadata:comment";
    public static final String METADATA_LINEAGE = "metadata:lineage";

    // ============ 元数据采集任务 ============
    public static final String COLLECT_VIEW = "collect:view";
    public static final String COLLECT_CREATE = "collect:create";
    public static final String COLLECT_UPDATE = "collect:update";
    public static final String COLLECT_DELETE = "collect:delete";
    public static final String COLLECT_EXECUTE = "collect:execute";
    public static final String COLLECT_HISTORY = "collect:history";

    // ============ 数据标准 ============
    public static final String STANDARD_VIEW = "standard:view";
    public static final String STANDARD_CREATE = "standard:create";
    public static final String STANDARD_UPDATE = "standard:update";
    public static final String STANDARD_DELETE = "standard:delete";

    // ============ 标准合规 ============
    public static final String COMPLIANCE_VIEW = "compliance:view";
    public static final String COMPLIANCE_HANDLE = "compliance:handle";

    // ============ 质量规则 ============
    public static final String QUALITY_RULE_VIEW = "quality_rule:view";
    public static final String QUALITY_RULE_CREATE = "quality_rule:create";
    public static final String QUALITY_RULE_UPDATE = "quality_rule:update";
    public static final String QUALITY_RULE_DELETE = "quality_rule:delete";

    // ============ 质量任务 ============
    public static final String QUALITY_JOB_VIEW = "quality_job:view";
    public static final String QUALITY_JOB_CREATE = "quality_job:create";
    public static final String QUALITY_JOB_UPDATE = "quality_job:update";
    public static final String QUALITY_JOB_DELETE = "quality_job:delete";
    public static final String QUALITY_JOB_EXECUTE = "quality_job:execute";
    public static final String QUALITY_JOB_HISTORY = "quality_job:history";

    // ============ 质量结果 ============
    public static final String QUALITY_RESULT_SCORE = "quality_result:score";
    public static final String QUALITY_RESULT_REPORT = "quality_result:report";

    // ============ 资产目录 ============
    public static final String ASSET_VIEW = "asset:view";
    public static final String ASSET_COLLAB = "asset:collab";
    public static final String ASSET_COMMENT = "asset:comment";
    /** 数据资产治理（分类体系维护 / 分配分类·负责人），仅超管+治理员（PRD §6.2.1 矩阵未单列，Sprint 7 DC-02/05 既有能力） */
    public static final String ASSET_MANAGE = "asset:manage";

    // ============ SQL 查询终端 ============
    public static final String SQL_EXECUTE = "sql:execute";
    public static final String SQL_EXPORT = "sql:export";
    public static final String SQL_HISTORY = "sql:history";

    // ============ 数据 API ============
    public static final String API_VIEW = "api:view";
    public static final String API_CREATE = "api:create";
    public static final String API_UPDATE = "api:update";
    public static final String API_PUBLISH = "api:publish";
    public static final String API_DELETE = "api:delete";
    public static final String API_STATS = "api:stats";

    // ============ API Key 管理 ============
    public static final String API_KEY_VIEW = "api_key:view";
    public static final String API_KEY_CREATE = "api_key:create";
    public static final String API_KEY_TOGGLE = "api_key:toggle";
    public static final String API_KEY_DELETE = "api_key:delete";

    // ============ 数据分级分类 ============
    public static final String SENSITIVITY_VIEW = "sensitivity:view";
    public static final String SENSITIVITY_CHANGE = "sensitivity:change";
    public static final String SENSITIVITY_BATCH_CHANGE = "sensitivity:batch_change";

    // ============ 告警中心 ============
    public static final String ALERT_VIEW = "alert:view";
    public static final String ALERT_RULE_MANAGE = "alert:rule_manage";

    // ============ 系统管理类（仅超管，不开放自定义角色） ============
    public static final String USER_VIEW = "user:view";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_TOGGLE = "user:toggle";
    public static final String USER_RESET_PWD = "user:reset_pwd";
    public static final String ROLE_VIEW = "role:view";
    public static final String ROLE_CREATE = "role:create";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";
    public static final String DATA_PERMISSION_MANAGE = "data_permission:manage";
    public static final String AUDIT_VIEW = "audit:view";
    public static final String QUEUE_MANAGE = "queue:manage";
}
