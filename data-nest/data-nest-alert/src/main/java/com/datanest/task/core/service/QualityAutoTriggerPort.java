package com.datanest.task.core.service;

/**
 * 质量任务自动触发 SPI（接口契约）。
 * <p>
 * 用于切断「告警/执行域 → 治理编排域」的双向依赖：
 * 告警执行链路（如 {@link DagAlertExecutionListener}）在对象成功完成后需要触发绑定该对象的
 * 质量任务自动检查，但不直接依赖治理模块的 {@code QualityAutoTriggerService} 实现。
 * <p>
 * 该接口在告警模块定义，由治理模块提供实现（governance 的 QualityAutoTriggerService 实现本接口），
 * Spring 通过接口注入，保持依赖方向单向（governance → alert）。
 */
public interface QualityAutoTriggerPort {

    /** 自动触发对象类型（与 quality_job.auto_trigger_object_type 对应） */
    String OBJECT_TYPE_DAG_NODE = "DAG_NODE";
    String OBJECT_TYPE_SYNC_JOB = "SYNC_JOB";
    String OBJECT_TYPE_COLLECT_TASK = "COLLECT_TASK";

    /**
     * 对象成功完成后触发绑定它的启用质量任务（AUTO_TRIGGER）。
     *
     * @param objectType 对象类型：DAG_NODE / SYNC_JOB / COLLECT_TASK
     * @param objectId   对象主键 ID
     */
    void triggerOnSuccess(String objectType, Long objectId);
}
