package com.datanest.governance.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.governance.entity.QualityJob;
import com.datanest.governance.entity.QualityRule;
import com.datanest.governance.mapper.QualityJobMapper;
import com.datanest.governance.mapper.QualityRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * 质量检查调度触发服务（Sprint 8 执行层，调度中心已切换为 PowerJob）。
 * <p>
 * 手动/自动触发统一入口：把质量任务（或单规则）通过 {@link SchedulerClient#triggerJob} 投递给
 * app-worker 上的 {@code qualityCheckExecuteHandler} 异步执行。质量任务定时调度（startSchedule）
 * 由 {@link QualityJobService} 独立注册带 cron 的调度任务，本服务负责执行时的按需注册与触发。
 * <p>
 * 参照 {@code SyncJobTriggerService}：按需注册（scheduler_job_id 为空时 registerJob 并回写）+ triggerJob。
 */
@Service
public class QualityCheckTriggerService {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckTriggerService.class);
    private static final String HANDLER_NAME = "qualityCheckExecuteHandler";
    private static final String TRIGGER_TYPE_CRON = "CRON";
    private static final String TRIGGER_TYPE_NONE = "NONE";
    /** 共享单规则执行任务的 jobName（server 端按此名识别复用，勿随意修改） */
    private static final String SINGLE_RULE_JOB_NAME = "质量单规则执行";

    @Value("${datanest.engineering.worker-appname:data-nest-worker}")
    private String workerAppName;

    private final QualityJobMapper jobMapper;
    private final QualityRuleMapper ruleMapper;
    private final SchedulerClient schedulerClient;

    /** 共享的单规则执行调度任务 ID（内存缓存，数据源以 server 端为准，见 ensureSingleRuleJob） */
    private volatile Long singleRuleJobId;

    public QualityCheckTriggerService(QualityJobMapper jobMapper,
                                      QualityRuleMapper ruleMapper,
                                      SchedulerClient schedulerClient) {
        this.jobMapper = jobMapper;
        this.ruleMapper = ruleMapper;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 触发一个质量任务执行（手动 MANUAL / 自动 AUTO_TRIGGER）。
     */
    public void triggerJob(Long jobId, String triggerType) {
        QualityJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_NOT_FOUND, "质量任务不存在: " + jobId);
        }
        Long schedulerJobId = job.getSchedulerJobId();
        if (schedulerJobId == null) {
            schedulerJobId = registerQualityJob(job);
            jobMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QualityJob>()
                    .eq("id", jobId).set("scheduler_job_id", schedulerJobId));
        }
        // param 格式：jobId[:triggerType]，供处理器区分触发方式（默认 MANUAL）；
        // 手动触发走 instanceParams（非空优先于 jobParams），格式保持不变
        String param = (triggerType == null || triggerType.isBlank())
                ? String.valueOf(jobId)
                : jobId + ":" + triggerType;
        schedulerClient.triggerJob(schedulerJobId, param);
        logger.info("已触发质量任务执行: jobId={}, triggerType={}, schedulerJobId={}", jobId, triggerType, schedulerJobId);
    }

    /**
     * 触发单条质量规则执行（手动 MANUAL）。param 传 {@code rule:<ruleId>}。
     */
    public void triggerRule(Long ruleId, String triggerType) {
        QualityRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + ruleId);
        }
        Long schedulerJobId = null;
        // 优先复用所属任务已注册的调度任务（若存在）
        if (rule.getJobId() != null) {
            QualityJob job = jobMapper.selectById(rule.getJobId());
            if (job != null && job.getSchedulerJobId() != null) {
                schedulerJobId = job.getSchedulerJobId();
            }
        }
        if (schedulerJobId == null) {
            schedulerJobId = ensureSingleRuleJob();
        }
        schedulerClient.triggerJob(schedulerJobId, "rule:" + ruleId);
        logger.info("已触发单规则质量检查: ruleId={}, triggerType={}, schedulerJobId={}", ruleId, triggerType, schedulerJobId);
    }

    /**
     * 按需注册质量任务的调度任务（无 cron 或带 cron 均可，scheduleEnabled=false 不自动调度，
     * 手动/自动经 triggerJob 触发；定时调度由 startSchedule 管理）。
     */
    private Long registerQualityJob(QualityJob job) {
        String cron = job.getCron();
        String triggerType = StringUtils.hasText(cron) ? TRIGGER_TYPE_CRON : TRIGGER_TYPE_NONE;
        return schedulerClient.registerJob(workerAppName, HANDLER_NAME, job.getId(), job.getName(),
                cron, triggerType, false, 0, 0);
    }

    /**
     * 确保存在一个共享的单规则执行调度任务（避免每单规则一个孤儿任务）。
     * <p>
     * 持久化方案：不再只依赖本进程内存缓存——使用时先按 jobName 经
     * {@link SchedulerClient#fetchAllJob} 查 server 端是否已存在同名任务，存在则复用其 jobId
     * 并刷新缓存；不存在才注册。内存缓存仅作性能优化，server 端数据为准，
     * 服务重启或调度中心被外部清理后仍能正确收敛到同一个共享任务。
     */
    private Long ensureSingleRuleJob() {
        if (singleRuleJobId != null) {
            return singleRuleJobId;
        }
        synchronized (this) {
            if (singleRuleJobId != null) {
                return singleRuleJobId;
            }
            Long existingId = findJobIdByName(SINGLE_RULE_JOB_NAME);
            if (existingId != null) {
                singleRuleJobId = existingId;
                logger.info("复用 server 端已存在的共享单规则质量检查任务: jobName={}, schedulerJobId={}",
                        SINGLE_RULE_JOB_NAME, existingId);
                return singleRuleJobId;
            }
            Long jobId = schedulerClient.registerJob(workerAppName, HANDLER_NAME, null, SINGLE_RULE_JOB_NAME,
                    null, TRIGGER_TYPE_NONE, false, 0, 0);
            singleRuleJobId = jobId;
            logger.info("已注册共享单规则质量检查调度任务: schedulerJobId={}", jobId);
            return singleRuleJobId;
        }
    }

    /**
     * 按 jobName 在 worker App 下查询调度任务 ID（fetchAllJob 全量拉取后本地过滤），
     * 未命中返回 null；查询失败直接抛错，避免误判「不存在」而重复注册。
     */
    private Long findJobIdByName(String jobName) {
        for (JsonNode job : schedulerClient.fetchAllJob(workerAppName)) {
            if (jobName.equals(job.path("jobName").asText())) {
                return job.path("id").asLong();
            }
        }
        return null;
    }
}
