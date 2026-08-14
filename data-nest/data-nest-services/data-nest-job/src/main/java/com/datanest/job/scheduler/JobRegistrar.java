package com.datanest.job.scheduler;

import com.datanest.common.scheduler.SchedulerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时通过 SchedulerClient（PowerJob OpenAPI）注册/更新平台定时任务（ensure 语义）。
 * <p>
 * 任务的 processorInfo 即 handler 名，执行时由 TechPowerJobRouterFactory
 * 路由到实现 PlatformJobHandler 接口的同名 Spring Bean。
 */
@Component
public class JobRegistrar implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(JobRegistrar.class);

    /** PowerJob App 名（与 powerjob.worker.app-name 一致，App 已在 server 预置） */
    private static final String APP_NAME = "data-nest-job";

    @Value("${datanest.job.dag-sync.cron:0/30 * * * * ?}")
    private String dagSyncCron;

    @Value("${datanest.job.dag-timeout-alert.cron:0 * * * * ?}")
    private String dagTimeoutAlertCron;

    @Value("${datanest.job.quality-auto-trigger-reconcile.cron:0 0/10 * * * ?}")
    private String qualityAutoTriggerReconcileCron;

    @Value("${datanest.job.doris-catalog-auto-refresh.cron:0/30 * * * * ?}")
    private String dorisCatalogAutoRefreshCron;

    private final SchedulerClient schedulerClient;

    public JobRegistrar(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 预定义的平台定时任务：handler -> cron（cron 配置键与 XXL-JOB 时期一致）
        Map<String, String> platformJobs = new LinkedHashMap<>();
        platformJobs.put("dataSourceStatusRefreshHandler", "0 0/5 * * * ?");
        platformJobs.put("syncHistoryCleanupHandler", "0 0 2 * * ?");
        platformJobs.put("collectHistoryCleanupHandler", "0 30 2 * * ?");
        // Sprint 3：DS 任务实例状态同步（默认 30s 兜底；handler 内有自适应触发，仍有 RUNNING 时缩短到 5s）
        platformJobs.put("dagExecutionSyncHandler", dagSyncCron);
        // Sprint 3：DAG 执行历史清理（每天凌晨 3 点，保留 30 天）
        platformJobs.put("dagExecutionHistoryCleanupHandler", "0 0 3 * * ?");
        // Sprint 5：血缘记录清理（默认每天凌晨 3 点 30 分，保留 90 天）
        platformJobs.put("lineageRecordCleanupHandler", "0 30 3 * * ?");
        // Sprint 5：告警发送历史清理（默认每天凌晨 4 点，保留 90 天）
        platformJobs.put("alertHistoryCleanupHandler", "0 0 4 * * ?");
        // 卡死 RUNNING 收割（每小时，阈值 datanest.task.stuck-running-timeout-minutes 默认 120 分钟）
        platformJobs.put("stuckExecutionReaperHandler", "0 0 * * * ?");
        // 同步任务持久化重试扫描（每小时，实际触发时间 = next_retry_at 之后的第一个整点扫描周期）
        platformJobs.put("syncJobRetryHandler", "0 10 * * * ?");
        // Sprint 4：DAG 节点超时告警扫描（默认每分钟）
        platformJobs.put("dagNodeTimeoutAlertHandler", dagTimeoutAlertCron);
        // Sprint 6：标准合规定时扫描（默认每天凌晨 2 点 30 分，扫全部在线数据源）
        platformJobs.put("standardComplianceCheckHandler", "0 30 2 * * ?");
        // Sprint 6 补全：质量检查历史清理（默认每天凌晨 4 点 30 分，保留 30 天）
        platformJobs.put("qualityCheckHistoryCleanupHandler", "0 30 4 * * ?");
        // Sprint 8 F1：资产热度记录清理（默认每天凌晨 4 点 40 分，保留 90 天）
        platformJobs.put("assetViewLogCleanupHandler", "0 40 4 * * ?");
        // Sprint 8 验收反馈（2026-08-11）：Doris 湖仓 catalog 自动刷新（默认每 30s；
        // realtime 侧仅存在 RUNNING 管道时才真正 REFRESH，无运行管道不空转）
        platformJobs.put("dorisCatalogAutoRefreshHandler", dorisCatalogAutoRefreshCron);
        // 微服务化阶段 2：质量自动触发漏触发对账补发（默认每 10 分钟，窗口 2 小时）
        platformJobs.put("qualityAutoTriggerReconcileHandler", qualityAutoTriggerReconcileCron);
        // Sprint 10 F1：SQL 查询历史清理（默认每天凌晨 3 点 50 分，保留 30 天，data-service 经 Feign 清理）
        platformJobs.put("sqlHistoryCleanupHandler", "0 50 3 * * ?");
        // Sprint 10 F3 补全：API 调用明细清理（默认每天凌晨 4 点 50 分，保留 30 天，data-service 经 Feign 清理）
        platformJobs.put("apiCallLogCleanupHandler", "0 50 4 * * ?");

        logger.info("Ensuring platform jobs registered in PowerJob, count={}", platformJobs.size());

        for (Map.Entry<String, String> entry : platformJobs.entrySet()) {
            String handlerName = entry.getKey();
            String cron = entry.getValue();
            try {
                Long jobId = schedulerClient.saveOrUpdateCronJob(APP_NAME, handlerName, resolveJobDesc(handlerName), cron);
                logger.info("Ensured platform job: handler={}, jobId={}, cron={}", handlerName, jobId, cron);
            } catch (Exception e) {
                logger.error("Failed to ensure platform job: handler={}", handlerName, e);
            }
        }
    }

    private String resolveJobDesc(String handlerName) {
        return switch (handlerName) {
            case "dataSourceStatusRefreshHandler" -> "数据源状态定时刷新";
            case "syncHistoryCleanupHandler" -> "同步任务历史清理";
            case "collectHistoryCleanupHandler" -> "采集任务历史清理";
            case "dagExecutionSyncHandler" -> "DAG 执行状态同步";
            case "dagExecutionHistoryCleanupHandler" -> "DAG 执行历史清理";
            case "lineageRecordCleanupHandler" -> "血缘记录清理";
            case "alertHistoryCleanupHandler" -> "告警发送历史清理";
            case "stuckExecutionReaperHandler" -> "卡死 RUNNING 执行收割";
            case "syncJobRetryHandler" -> "同步任务失败重试扫描";
            case "dagNodeTimeoutAlertHandler" -> "DAG 节点超时告警扫描";
            case "standardComplianceCheckHandler" -> "标准合规定时扫描";
            case "qualityCheckHistoryCleanupHandler" -> "质量检查历史清理";
            case "assetViewLogCleanupHandler" -> "资产热度记录清理";
            case "dorisCatalogAutoRefreshHandler" -> "Doris 湖仓 catalog 自动刷新";
            case "qualityAutoTriggerReconcileHandler" -> "质量自动触发对账补发";
            case "sqlHistoryCleanupHandler" -> "SQL 查询历史清理";
            case "apiCallLogCleanupHandler" -> "API 调用明细清理";
            default -> handlerName;
        };
    }
}
