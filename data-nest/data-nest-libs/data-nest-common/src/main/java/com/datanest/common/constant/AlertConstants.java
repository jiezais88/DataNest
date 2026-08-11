package com.datanest.common.constant;

/**
 * Sprint 5：通用告警中心常量。
 * <p>
 * 微服务化 6.1：由 task-core(entity) 与 alert-service 中两份内容相同的副本合并迁入 common 模块。
 */
public final class AlertConstants {

    private AlertConstants() {
    }

    /** 告警对象类型 */
    public static final String OBJECT_TYPE_DAG = "DAG";
    public static final String OBJECT_TYPE_SYNC_JOB = "SYNC_JOB";
    public static final String OBJECT_TYPE_COLLECT_TASK = "COLLECT_TASK";
    public static final String OBJECT_TYPE_QUALITY = "QUALITY";
    /** Sprint 9：实时 CDC 管道（流处理告警） */
    public static final String OBJECT_TYPE_CDC_PIPELINE = "CDC_PIPELINE";

    /** 告警触发条件 */
    public static final String ALERT_FAILURE = "FAILURE";
    public static final String ALERT_TIMEOUT = "TIMEOUT";
    public static final String ALERT_SUCCESS = "SUCCESS";
    /** Sprint 9：CDC 管道同步延迟超阈值 */
    public static final String ALERT_LAG_EXCEEDED = "LAG_EXCEEDED";
    /** Sprint 9：CDC 管道 Flink 作业被外部停止/丢失 */
    public static final String ALERT_EXTERNAL_STOP = "EXTERNAL_STOP";

    /** 邮件发送状态 */
    public static final String SEND_STATUS_SUCCESS = "SUCCESS";
    public static final String SEND_STATUS_FAILED = "FAILED";

    /** 对象类型显示名 */
    public static final String DISPLAY_DAG = "DAG";
    public static final String DISPLAY_SYNC_JOB = "同步任务";
    public static final String DISPLAY_COLLECT_TASK = "采集任务";
    public static final String DISPLAY_QUALITY = "质量任务";
    public static final String DISPLAY_CDC_PIPELINE = "CDC 管道";

    /** 质量检查分级判定 */
    public static final String QUALITY_LEVEL_PASS = "PASS";
    public static final String QUALITY_LEVEL_WARNING = "WARNING";
    public static final String QUALITY_LEVEL_SEVERE = "SEVERE";
    public static final String QUALITY_LEVEL_UNAVAILABLE = "UNAVAILABLE";
}
