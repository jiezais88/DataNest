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

/**
 * 质量检查 XXL-JOB 触发服务（Sprint 8 执行层）。
 * <p>
 * 手动/自动触发统一入口：把质量任务（或单规则）通过 {@link SchedulerClient#triggerJob} 投递给
 * app-worker 上的 {@code qualityCheckExecuteHandler} 异步执行。质量任务定时调度（startSchedule）
 * 由 {@link QualityJobService} 独立注册带 cron 的 XXL-JOB，本服务负责执行时的按需注册与触发。
 * <p>
 * 参照 {@link SyncJobTriggerService}：按需注册（xxl_job_id 为空时 registerJob 并回写）+ triggerJob。
 */
@Service
public class QualityCheckTriggerService {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckTriggerService.class);
    private static final String HANDLER_NAME = "qualityCheckExecuteHandler";
    private static final String TRIGGER_TYPE_CRON = "CRON";
    private static final String TRIGGER_TYPE_NONE = "NONE";

    @Value("${datanest.engineering.worker-appname:data-nest-worker}")
    private String workerAppName;

    private final QualityJobMapper jobMapper;
    private final QualityRuleMapper ruleMapper;
    private final SchedulerClient schedulerClient;

    /** 共享的单规则执行 XXL-JOB ID（惰性注册并缓存，避免每单规则一个孤儿任务） */
    private volatile Integer singleRuleJobId;

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
        Integer xxlJobId = job.getXxlJobId();
        if (xxlJobId == null) {
            xxlJobId = registerQualityJob(job);
            jobMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QualityJob>()
                    .eq("id", jobId).set("xxl_job_id", xxlJobId));
        }
        // executorParam 格式：jobId[:triggerType]，供 handler 区分触发方式（默认 MANUAL）
        String param = (triggerType == null || triggerType.isBlank())
                ? String.valueOf(jobId)
                : jobId + ":" + triggerType;
        schedulerClient.triggerJob(xxlJobId, param);
        logger.info("已触发质量任务执行: jobId={}, triggerType={}, xxlJobId={}", jobId, triggerType, xxlJobId);
    }

    /**
     * 触发单条质量规则执行（手动 MANUAL）。param 传 {@code rule:<ruleId>}。
     */
    public void triggerRule(Long ruleId, String triggerType) {
        QualityRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + ruleId);
        }
        Integer xxlJobId = null;
        // 优先复用所属任务已注册的 XXL-JOB（若存在）
        if (rule.getJobId() != null) {
            QualityJob job = jobMapper.selectById(rule.getJobId());
            if (job != null && job.getXxlJobId() != null) {
                xxlJobId = job.getXxlJobId();
            }
        }
        if (xxlJobId == null) {
            xxlJobId = ensureSingleRuleJob();
        }
        schedulerClient.triggerJob(xxlJobId, "rule:" + ruleId);
        logger.info("已触发单规则质量检查: ruleId={}, triggerType={}, xxlJobId={}", ruleId, triggerType, xxlJobId);
    }

    /**
     * 按需注册质量任务的 XXL-JOB（无 cron 或带 cron 均可，scheduleEnabled=false 不自动调度，
     * 手动/自动经 triggerJob 触发；定时调度由 startSchedule 管理）。
     */
    private Integer registerQualityJob(QualityJob job) {
        String cron = job.getCron();
        String triggerType = StringUtils.hasText(cron) ? TRIGGER_TYPE_CRON : TRIGGER_TYPE_NONE;
        return schedulerClient.registerJob(workerAppName, HANDLER_NAME, job.getId(), job.getName(),
                cron, triggerType, false, 0, 0);
    }

    /**
     * 确保存在一个共享的单规则执行 XXL-JOB（惰性注册并缓存，避免孤儿任务堆积）。
     */
    private Integer ensureSingleRuleJob() {
        if (singleRuleJobId != null) {
            return singleRuleJobId;
        }
        synchronized (this) {
            if (singleRuleJobId != null) {
                return singleRuleJobId;
            }
            int jobGroup = schedulerClient.ensureJobGroup(workerAppName);
            // 复用 worker 组下已存在的同名 handler 任务（可能是任务级注册的，param 由 triggerJob 覆盖）
            var existing = schedulerClient.findJobByHandler(jobGroup, HANDLER_NAME);
            if (existing != null) {
                singleRuleJobId = existing.path("id").asInt();
                return singleRuleJobId;
            }
            Integer jobId = schedulerClient.registerJob(workerAppName, HANDLER_NAME, null, "质量单规则执行",
                    null, TRIGGER_TYPE_NONE, false, 0, 0);
            singleRuleJobId = jobId;
            logger.info("已注册共享单规则质量检查 XXL-JOB: xxlJobId={}", jobId);
            return singleRuleJobId;
        }
    }
}
