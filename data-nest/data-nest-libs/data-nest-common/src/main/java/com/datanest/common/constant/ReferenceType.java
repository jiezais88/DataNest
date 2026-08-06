package com.datanest.common.constant;

/**
 * 数据源引用类型枚举。
 */
public enum ReferenceType {

    COLLECT("COLLECT", "元数据采集任务"),
    SYNC("SYNC", "批量数据同步任务"),
    QUALITY_RULE("QUALITY_RULE", "质量规则");

    private final String code;
    private final String label;

    ReferenceType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ReferenceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ReferenceType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
