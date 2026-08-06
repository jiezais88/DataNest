package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 元数据表/字段在本地库中的来源状态。
 * ONLINE=在线可用，OFFLINE=已删除或不可用。
 */
public enum MetadataSourceStatus {

    ONLINE("ONLINE", "在线"),
    OFFLINE("OFFLINE", "离线");

    private final String code;
    private final String label;

    MetadataSourceStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static MetadataSourceStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (MetadataSourceStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(MetadataSourceStatus::getCode).collect(Collectors.toList());
    }
}
