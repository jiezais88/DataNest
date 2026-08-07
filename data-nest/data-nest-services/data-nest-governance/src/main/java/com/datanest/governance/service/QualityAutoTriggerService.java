package com.datanest.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.governance.entity.QualityJob;
import com.datanest.governance.mapper.QualityJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 质量任务自动触发服务（Sprint 8 执行层）。
 * <p>
 * 在 DAG 节点 / 同步任务 / 采集任务「成功」完成后调用 {@link #triggerOnSuccess}，
 * 找出绑定该对象且启用了自动触发的质量任务，逐个触发执行（AUTO_TRIGGER）。
 * 触发失败不影响主任务执行结果（整体 try-catch 包裹，仅记日志）。
 * <p>
 * 微服务化改造：告警域已迁出为独立 alert-service，原告警模块的自动触发端口接口随之删除，
 * 本类不再实现该接口；DAG 节点的自动触发由 alert-service 经 governance 内部接口回调本方法。
 */
@Service
public class QualityAutoTriggerService {

    private static final Logger logger = LoggerFactory.getLogger(QualityAutoTriggerService.class);

    /** 自动触发对象类型（与 quality_job.auto_trigger_object_type 对应） */
    public static final String OBJECT_TYPE_DAG_NODE = "DAG_NODE";
    public static final String OBJECT_TYPE_SYNC_JOB = "SYNC_JOB";
    public static final String OBJECT_TYPE_COLLECT_TASK = "COLLECT_TASK";

    private static final String TRIGGER_TYPE_AUTO = "AUTO_TRIGGER";

    private final QualityJobMapper jobMapper;
    private final QualityCheckTriggerService triggerService;

    public QualityAutoTriggerService(QualityJobMapper jobMapper,
                                     QualityCheckTriggerService triggerService) {
        this.jobMapper = jobMapper;
        this.triggerService = triggerService;
    }

    /**
     * 对象成功完成后触发绑定它的启用质量任务。
     *
     * @param objectType 对象类型：DAG_NODE / SYNC_JOB / COLLECT_TASK
     * @param objectId   对象主键 ID（DAG 节点为 dag_node.id，其余为对应表主键）
     */
    public void triggerOnSuccess(String objectType, Long objectId) {
        if (objectType == null || objectId == null) {
            return;
        }
        List<QualityJob> jobs = jobMapper.selectList(new QueryWrapper<QualityJob>()
                .eq("enabled", 1)
                .eq("auto_trigger_enabled", 1)
                .eq("auto_trigger_object_type", objectType)
                .eq("auto_trigger_object_id", objectId));
        if (jobs.isEmpty()) {
            return;
        }
        for (QualityJob job : jobs) {
            try {
                triggerService.triggerJob(job.getId(), TRIGGER_TYPE_AUTO);
            } catch (Exception e) {
                // 自动触发失败不影响主任务执行结果
                logger.warn("质量任务自动触发失败: objectType={}, objectId={}, jobId={}, error={}",
                        objectType, objectId, job.getId(), e.getMessage());
            }
        }
    }
}
