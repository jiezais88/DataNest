package com.datanest.common.audit;

/**
 * 审计资源类型（PRD §6.1.1 十类操作对象）。
 * <p>
 * 枚举值作为 {@code audit_log.resource_type} 落库；中文 label 供前端下拉/展示。
 * 权限变更（ROLE）与执行队列（EXECUTION_QUEUE）本枚举先定义，埋点随 F2/F3 补齐。
 */
public enum AuditResourceType {

    USER("用户"),
    ROLE("角色"),
    DATASOURCE("数据源"),
    SYNC_JOB("批量同步任务"),
    DAG("DAG 编排"),
    SQL_QUERY("SQL 查询"),
    DATA_API("数据 API"),
    API_KEY("API Key"),
    SENSITIVITY("数据分级分类"),
    EXECUTION_QUEUE("执行队列"),
    SSO_CONFIG("身份认证");

    private final String label;

    AuditResourceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
