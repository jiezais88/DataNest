package com.datanest.engineering.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.scheduler.PowerJobWorkflowClient;
import com.datanest.engineering.entity.Dag;
import com.datanest.engineering.entity.DagExecution;
import com.datanest.engineering.entity.DagNode;
import com.datanest.engineering.entity.ExecutionQueue;
import com.datanest.engineering.entity.NodeExecution;
import com.datanest.engineering.mapper.DagExecutionMapper;
import com.datanest.engineering.mapper.DagMapper;
import com.datanest.engineering.mapper.DagNodeMapper;
import com.datanest.engineering.mapper.NodeExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 队列调度服务（Sprint 11 F3 任务资源队列）。
 * <p>
 * job 侧 QueueDispatcherHandler 每 5s 调 {@link #dispatchOnce()}：对每个有 WAITING 的队列，
 * 取待调度实例（priority DESC, created_at ASC）逐个补触发（预建 node_execution + PowerJob runWorkflow + 置 RUNNING），
 * 直到队列满（QU-6 同队列并发满时高优先先执行）。
 * <p>
 * 并发安全：触发前置 WAITING→RUNNING 条件更新（乐观锁），失败（被并发抢占）则跳过本轮，
 * 下轮对账继续；防止 job 多实例/重入时同一 WAITING 被重复触发。
 */
@Service
public class QueueDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(QueueDispatchService.class);

    /** DAG 相关节点 job 与工作流统一挂在 data-nest-worker App（appId=2） */
    private static final String WORKER_APP_NAME = "data-nest-worker";

    private final DagExecutionMapper dagExecutionMapper;
    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final PowerJobWorkflowClient powerJobWorkflowClient;
    private final ExecutionQueueService executionQueueService;
    private final TransactionTemplate transactionTemplate;

    public QueueDispatchService(DagExecutionMapper dagExecutionMapper,
                                DagMapper dagMapper,
                                DagNodeMapper dagNodeMapper,
                                NodeExecutionMapper nodeExecutionMapper,
                                PowerJobWorkflowClient powerJobWorkflowClient,
                                ExecutionQueueService executionQueueService,
                                PlatformTransactionManager transactionManager) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.powerJobWorkflowClient = powerJobWorkflowClient;
        this.executionQueueService = executionQueueService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 调度一轮：扫描全部队列，有空位的队列取 WAITING 补触发直到满。
     *
     * @return 本轮实际触发数
     */
    public int dispatchOnce() {
        List<ExecutionQueue> queues = executionQueueService.listAll();
        int triggered = 0;
        for (ExecutionQueue queue : queues) {
            int capacity = queue.getMaxConcurrency() == null ? 10 : queue.getMaxConcurrency();
            int running = executionQueueService.runningCount(queue.getQueueName());
            if (running >= capacity) {
                continue; // 队列满，本轮跳过
            }
            int slots = capacity - running;
            // 取该队列待调度 WAITING（按 priority DESC, created_at ASC，SQL 已按队列过滤，避免跨队列取数）
            List<DagExecution> waiting = dagExecutionMapper.selectWaitingToDispatch(queue.getQueueName(), slots);
            for (DagExecution execution : waiting) {
                try {
                    if (dispatchOne(execution)) {
                        triggered++;
                    }
                } catch (Exception e) {
                    logger.error("队列调度单实例失败: executionId={}, dagId={}, err={}",
                            execution.getId(), execution.getDagId(), e.getMessage());
                }
            }
        }
        if (triggered > 0) {
            logger.info("执行队列调度完成: 本轮触发 {} 个等待实例", triggered);
        }
        return triggered;
    }

    /**
     * 补触发单个等待实例：先条件更新 WAITING→RUNNING（乐观锁，防并发重复触发），
     * 成功者预建 node_execution + 调 PowerJob + 回写 wfInstanceId。
     * <p>
     * 事务边界：dispatchOnce 循环调用本方法属同类内部调用，@Transactional 代理不生效，
     * 故用 TransactionTemplate 显式开启事务包住「WAITING→RUNNING + node_execution 预建」，
     * 事务提交后再调 PowerJob runWorkflow（HTTP 调用不放 DB 事务里），
     * 失败则把 RUNNING 标 FAILED 补偿（对齐 trigger 现有补偿语义）。
     */
    public boolean dispatchOne(DagExecution execution) {
        if (execution == null || execution.getId() == null) {
            return false;
        }
        final Long executionId = execution.getId();
        final Long dagId = execution.getDagId();
        final Long[] dagWorkflowId = {null};
        final boolean[] dispatched = {false};

        transactionTemplate.executeWithoutResult(status -> {
            // 条件更新：仅当仍为 WAITING 且无 wfInstanceId 才置 RUNNING；返回 0 = 已被并发抢占，跳过
            int updated = dagExecutionMapper.update(null, new UpdateWrapper<DagExecution>()
                    .eq("id", executionId)
                    .eq("status", "WAITING")
                    .isNull("powerjob_wf_instance_id")
                    .set("status", "RUNNING")
                    .set("start_time", LocalDateTime.now()));
            if (updated == 0) {
                return;
            }
            Dag dag = dagMapper.selectById(dagId);
            if (dag == null || dag.getPowerjobWorkflowId() == null) {
                // DAG 已删/未同步，把该执行标 FAILED，避免等待池悬挂
                DagExecution failed = new DagExecution();
                failed.setStatus("FAILED");
                failed.setEndTime(LocalDateTime.now());
                failed.setErrorMessage("调度时 DAG 不存在或未同步到调度引擎");
                dagExecutionMapper.update(failed, new UpdateWrapper<DagExecution>().eq("id", executionId));
                logger.error("队列调度：DAG 不可用，执行标 FAILED: executionId={}, dagId={}", executionId, dagId);
                return;
            }
            dagWorkflowId[0] = dag.getPowerjobWorkflowId();
            // 预建 node_execution（对齐 trigger 路径）
            List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
            if (!nodes.isEmpty()) {
                List<NodeExecution> nes = new ArrayList<>(nodes.size());
                for (DagNode node : nodes) {
                    NodeExecution ne = new NodeExecution();
                    ne.setId(IdWorker.getId());
                    ne.setExecutionId(executionId);
                    ne.setNodeId(node.getNodeId());
                    ne.setNodeName(node.getNodeName());
                    ne.setNodeType(node.getNodeType());
                    ne.setStatus("WAITING");
                    nes.add(ne);
                }
                nodeExecutionMapper.insertBatch(nes);
            }
            dispatched[0] = true;
        });

        if (!dispatched[0] || dagWorkflowId[0] == null) {
            return false;
        }
        // 事务已提交，调 PowerJob 触发（HTTP 调用放事务外）
        Long wfInstanceId;
        try {
            String initParams = JSON.toJSONString(Map.of("dagExecutionId", executionId));
            wfInstanceId = powerJobWorkflowClient.runWorkflow(WORKER_APP_NAME, dagWorkflowId[0], initParams);
        } catch (Exception e) {
            String reason = "队列调度触发失败: " + e.getMessage();
            logger.error("队列调度 PowerJob 触发失败，执行标 FAILED: executionId={}", executionId, e);
            try {
                DagExecution failed = dagExecutionMapper.selectById(executionId);
                if (failed != null && "RUNNING".equalsIgnoreCase(failed.getStatus())) {
                    failed.setStatus("FAILED");
                    failed.setEndTime(LocalDateTime.now());
                    failed.setErrorMessage(reason);
                    if (failed.getStartTime() != null) {
                        failed.setDurationMs(Duration.between(failed.getStartTime(), failed.getEndTime()).toMillis());
                    }
                    dagExecutionMapper.updateById(failed);
                }
                // 节点从未实际运行，标为 SKIPPED
                NodeExecution skipped = new NodeExecution();
                skipped.setStatus("SKIPPED");
                skipped.setErrorMessage("DAG 触发失败，节点未运行");
                nodeExecutionMapper.update(skipped,
                        new UpdateWrapper<NodeExecution>()
                                .eq("execution_id", executionId)
                                .eq("status", "WAITING"));
            } catch (Exception ex) {
                logger.error("队列调度补偿标记 FAILED/SKIPPED 失败: executionId={}", executionId, ex);
            }
            return false; // 不抛给 job，下轮对账继续（该实例已 FAILED，不会再调度）
        }
        // 回写 wfInstanceId
        DagExecution fresh = dagExecutionMapper.selectById(executionId);
        if (fresh != null) {
            fresh.setPowerjobWfInstanceId(wfInstanceId);
            dagExecutionMapper.updateById(fresh);
        }
        logger.info("队列调度触发成功: executionId={}, dagId={}, queue={}, priority={}",
                executionId, dagId, execution.getQueueName(), execution.getPriority());
        return true;
    }

    /**
     * 对账兜底（PRD B6/技术文档 D-4）：将超过 {@code waitTimeoutMinutes} 仍停留在 WAITING 的实例强制置为 FAILED，
     * 防止 DAG 队列引用被删 / 调度器异常导致等待池永久悬挂。
     *
     * @return 强制收尾的实例数
     */
    @Transactional
    public int reapStuckWaiting(int waitTimeoutMinutes) {
        int thresholdMinutes = Math.max(1, waitTimeoutMinutes);
        List<DagExecution> waiting = dagExecutionMapper.selectAllWaiting();
        int reaped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (DagExecution execution : waiting) {
            if (execution.getCreatedAt() != null
                    && Duration.between(execution.getCreatedAt(), now).toMinutes() > thresholdMinutes) {
                int updated = dagExecutionMapper.update(null, new UpdateWrapper<DagExecution>()
                        .eq("id", execution.getId())
                        .eq("status", "WAITING")
                        .set("status", "FAILED")
                        .set("end_time", now)
                        .set("error_message", "等待执行超时，已被对账任务强制收尾"));
                if (updated > 0) {
                    reaped++;
                    logger.warn("队列对账：超时 WAITING 强制收尾 FAILED: executionId={}, dagId={}, 等待{}分钟",
                            execution.getId(), execution.getDagId(), thresholdMinutes);
                }
            }
        }
        if (reaped > 0) {
            logger.info("队列对账完成: 超时 WAITING 收尾 {} 个", reaped);
        }
        return reaped;
    }
}
