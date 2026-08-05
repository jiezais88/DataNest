package com.datanest.task.core.constant;

/**
 * Sprint 5：通用告警中心常量。
 */
public final class AlertConstants {

    private AlertConstants() {
    }

    /** 告警对象类型 */
    public static final String OBJECT_TYPE_DAG = "DAG";
    public static final String OBJECT_TYPE_SYNC_JOB = "SYNC_JOB";
    public static final String OBJECT_TYPE_COLLECT_TASK = "COLLECT_TASK";
    public static final String OBJECT_TYPE_QUALITY = "QUALITY";

    /** 告警触发条件 */
    public static final String ALERT_FAILURE = "FAILURE";
    public static final String ALERT_TIMEOUT = "TIMEOUT";
    public static final String ALERT_SUCCESS = "SUCCESS";

    /** 邮件发送状态 */
    public static final String SEND_STATUS_SUCCESS = "SUCCESS";
    public static final String SEND_STATUS_FAILED = "FAILED";

    /** 对象类型显示名 */
    public static final String DISPLAY_DAG = "DAG";
    public static final String DISPLAY_SYNC_JOB = "同步任务";
    public static final String DISPLAY_COLLECT_TASK = "采集任务";
    public static final String DISPLAY_QUALITY = "质量任务";

    /** 质量检查分级判定 */
    public static final String QUALITY_LEVEL_PASS = "PASS";
    public static final String QUALITY_LEVEL_WARNING = "WARNING";
    public static final String QUALITY_LEVEL_SEVERE = "SEVERE";
    public static final String QUALITY_LEVEL_UNAVAILABLE = "UNAVAILABLE";
}
