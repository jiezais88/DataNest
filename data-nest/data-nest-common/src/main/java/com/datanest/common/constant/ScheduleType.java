package com.datanest.common.constant;

/**
 * XXL-JOB 调度类型枚举。
 */
public enum ScheduleType {

    CRON("CRON", "Cron 调度"),
    NONE("NONE", "无调度");

    private final String code;
    private final String label;

    ScheduleType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ScheduleType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ScheduleType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }

    public static ScheduleType fromTriggerType(String triggerType) {
        if (TaskTriggerType.CRON.getCode().equalsIgnoreCase(triggerType)) {
            return CRON;
        }
        return NONE;
    }
}
