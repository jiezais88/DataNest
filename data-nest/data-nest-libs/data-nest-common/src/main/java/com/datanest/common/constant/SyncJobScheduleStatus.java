package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 批量同步任务调度状态枚举。
 */
public enum SyncJobScheduleStatus {

    NORMAL("NORMAL", "正常"),
    PAUSED("PAUSED", "已暂停");

    private final String code;
    private final String label;

    SyncJobScheduleStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static SyncJobScheduleStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (SyncJobScheduleStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(SyncJobScheduleStatus::getCode).collect(Collectors.toList());
    }

    public boolean isNormal() {
        return this == NORMAL;
    }

    public boolean isPaused() {
        return this == PAUSED;
    }
}
