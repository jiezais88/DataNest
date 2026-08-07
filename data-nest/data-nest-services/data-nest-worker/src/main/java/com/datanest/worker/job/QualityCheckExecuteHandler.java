package com.datanest.worker.job;

import com.datanest.task.core.service.QualityCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 质量检查执行 handler（Sprint 8 执行层，原 XXL-JOB 的 qualityCheckExecuteHandler）。
 * <p>
 * 注册在 app-worker（data-nest-worker 应用）。param 传 jobId（任务执行）或
 * {@code rule:<ruleId>}（单规则执行）。手动/定时/自动三种触发统一经该 handler 投递执行。
 * 定时调度由质量任务各自注册的带 cron 的 PowerJob 到点触发本 handler。
 */
@Component
public class QualityCheckExecuteHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckExecuteHandler.class);

    private static final String RULE_PREFIX = "rule:";

    private final QualityCheckService qualityCheckService;

    public QualityCheckExecuteHandler(QualityCheckService qualityCheckService) {
        this.qualityCheckService = qualityCheckService;
    }

    @Override
    public String getName() {
        return "qualityCheckExecuteHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("质量检查执行 handler 开始: param={}", param);
        Long batchId = dispatch(param);
        logger.info("质量检查完成: batchId={}", batchId);
    }

    private Long dispatch(String param) {
        if (param != null && param.startsWith(RULE_PREFIX)) {
            Long ruleId = Long.valueOf(param.substring(RULE_PREFIX.length()).trim());
            return qualityCheckService.executeRule(ruleId, "MANUAL");
        }
        if (param == null || param.isBlank()) {
            throw new IllegalArgumentException("质量检查执行缺少任务 ID: param=" + param);
        }
        // 任务 param 格式：jobId[:triggerType]
        //  - 手动/自动触发：显式传 jobId:MANUAL / jobId:AUTO_TRIGGER（带冒号）
        //  - 定时触发：注册时保存的 jobParams（纯 jobId，无冒号）→ 视为 SCHEDULED
        String[] parts = param.split(":", 2);
        Long jobId = Long.valueOf(parts[0].trim());
        String triggerType = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "SCHEDULED";
        return qualityCheckService.executeJob(jobId, triggerType);
    }
}
