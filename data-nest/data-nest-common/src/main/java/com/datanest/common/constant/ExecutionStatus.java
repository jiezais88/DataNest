package com.datanest.common.constant;

/**
 * 任务执行结果状态枚举。
 */
public enum ExecutionStatus {

    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    RUNNING("RUNNING", "运行中");

    private final String code;
    private final String label;

    ExecutionStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ExecutionStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ExecutionStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailed() {
        return this == FAILED;
    }
}
