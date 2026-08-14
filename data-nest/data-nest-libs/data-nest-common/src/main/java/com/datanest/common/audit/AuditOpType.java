package com.datanest.common.audit;

/**
 * 审计操作类型（PRD §6.1.1 各资源的具体动作）。
 * <p>
 * 枚举值作为 {@code audit_log.op_type} 落库；中文 label 供前端下拉/展示。
 */
public enum AuditOpType {

    CREATE("创建"),
    UPDATE("修改"),
    DELETE("删除"),
    EXECUTE("执行"),
    STOP("停止"),
    TEST("测试连接"),
    CHANGE_LEVEL("改级"),
    PUBLISH("发布"),
    OFFLINE("下线"),
    ENABLE("启用"),
    DISABLE("禁用"),
    RESET_PASSWORD("重置密码"),
    TRIGGER("手动触发");

    private final String label;

    AuditOpType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
