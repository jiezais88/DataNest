package com.datanest.common.constant;

/**
 * 元数据来源类型枚举。
 */
public enum SourceType {

    EXTERNAL("EXTERNAL", "外部数据源"),
    BUILTIN_DORIS("BUILTIN_DORIS", "内置 Doris");

    private final String code;
    private final String label;

    SourceType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static SourceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (SourceType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
