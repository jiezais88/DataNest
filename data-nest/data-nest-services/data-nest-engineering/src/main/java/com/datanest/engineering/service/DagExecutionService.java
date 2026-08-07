package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.scheduler.PowerJobWorkflowClient;
import com.datanest.engineering.api.dto.DagEdgeInfo;
import com.datanest.engineering.dto.*;
import com.datanest.engineering.entity.*;
import com.datanest.engineering.mapper.*;
import com.datanest.task.core.service.DagEdgeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import static java.time.ZoneId.of;

/**
 * DAG 执行服务
 * - 手动触发（P3：PowerJob runWorkflow，替换 DS startWorkflowInstance）
 * - 提供执行历史查询（执行状态轮询同步由 job 侧 DagExecutionSyncService 负责）
 *
 * Sprint 3 修复：
 * - P1-3：trigger 用 DB 唯一索引保证并发安全
 * - P1-1：stop 同步清理子节点为 SKIPPED
 * - 性能1：listByDag 批量查 Dag 避免 N+1
 */
@Service
public class DagExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(DagExecutionService.class);

    /** DAG 相关节点 job 与工作流统一挂在 data-nest-worker App（appId=2） */
    private static final String WORKER_APP_NAME = "data-nest-worker";

    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagProjectMapper dagProjectMapper;
    private final PowerJobWorkflowClient powerJobWorkflowClient;
    private final SyncJobService syncJobService;
    private final DagService dagService;
    private final DagParameterService dagParameterService;

    public DagExecutionService(DagMapper dagMapper, DagNodeMapper dagNodeMapper,
                               DagEdgeMapper dagEdgeMapper,
                               DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                               DagProjectMapper dagProjectMapper,
                               PowerJobWorkflowClient powerJobWorkflowClient,
                               SyncJobService syncJobService,
                               DagService dagService,
                               DagParameterService dagParameterService) {
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagProjectMapper = dagProjectMapper;
        this.powerJobWorkflowClient = powerJobWorkflowClient;
        this.syncJobService = syncJobService;
        this.dagService = dagService;
        this.dagParameterService = dagParameterService;
    }

    /**
     * 手动触发 DAG 执行。
     * <p>
     * 顺序（P1-3 并发安全 + 事务内不调远端）：
     * 1. 事务内先插 RUNNING 占位行，靠 uk_dag_execution_running 唯一索引拒绝并发触发
     * 2. 同事务预创建 node_execution（WAITING）
     * 3. 事务提交后才调 PowerJob runWorkflow（initParams 带 dagExecutionId），成功则回写 powerjobWfInstanceId；
     *    失败则把占位行标 FAILED 补偿（DB 已提交不能回滚），并向调用方抛可感知的业务异常
     * <p>
     * 旧顺序（先远端后插行）在并发下会产生无人轮询的孤儿执行。
     */
    @Transactional
    public DagExecutionDTO trigger(Long dagId) {
        return trigger(dagId, null);
    }

    /** dag_edge 实体 → 边快照 DTO（DagEdgeSnapshot 3.3 起只接收 DTO） */
    private static DagEdgeInfo toEdgeInfo(DagEdge edge) {
        DagEdgeInfo info = new DagEdgeInfo();
        info.setSourceNodeId(edge.getSourceNodeId());
        info.setTargetNodeId(edge.getTargetNodeId());
        return info;
    }

    @Transactional
    public DagExecutionDTO trigger(Long dagId, Map<String, Object> manualParams) {
        Dag dag = dagMapper.selectById(dagId);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        if (!"ENABLED".equalsIgnoreCase(dag.getStatus())) {
            throw new BusinessException(ErrorCode.DAG_DISABLED);
        }

        // 若 PowerJob 工作流未上线（OFFLINE 或尚未同步），先自动同步（懒注册）；同步失败会抛异常，调用方可见具体原因。
        // 注意存量 DAG：DS 时代 release_state=ONLINE 但 powerjob_workflow_id 为空，同样需要先同步。
        if (!"ONLINE".equalsIgnoreCase(dag.getReleaseState()) || dag.getPowerjobWorkflowId() == null) {
            logger.info("DAG 触发前检测到 release_state={}、powerjobWorkflowId={}，先同步到 PowerJob: dagId={}",
                    dag.getReleaseState(), dag.getPowerjobWorkflowId(), dagId);
            dagService.syncToScheduler(dagId);
            dag = dagMapper.selectById(dagId);
            if (dag == null) {
                throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
            }
        }
        if (dag.getPowerjobWorkflowId() == null) {
            throw new BusinessException(ErrorCode.DS_API_ERROR, "DAG 未同步到 PowerJob");
        }

        // 1. 先入库 dag_execution RUNNING 占位行（P1-3：靠 DB 唯一索引 uk_dag_execution_running 保证并发安全）
        //    powerjobWfInstanceId 暂为 null，事务提交后调 PowerJob 成功再回写
        DagExecution execution = new DagExecution();
        execution.setDagId(dagId);
        // 手动触发时固定记录为 MANUAL，避免执行历史里显示成定时触发
        execution.setTriggerType("MANUAL");
        execution.setStatus("RUNNING");
        execution.setStartTime(LocalDateTime.now());
        execution.setCreatedBy(currentUserId());
        execution.setCreatedAt(LocalDateTime.now());
        // 解析并保存本次执行的参数（手动覆盖 > 默认值 > 系统变量）
        Map<String, Object> resolvedParams = dagParameterService.resolveParams(dagId, manualParams);
        execution.setResolvedParams(JSON.toJSONString(resolvedParams));
        // 边快照：历史视图（run-view）用快照渲染边，避免后续删节点导致历史实例连线丢失
        // （engineering 本地归属 dag_edge，沿用本地查询并映射为 DagEdgeInfo DTO 后序列化）
        execution.setEdgeSnapshot(DagEdgeSnapshot.capture(
                dagEdgeMapper.selectByDagId(dagId).stream().map(DagExecutionService::toEdgeInfo).toList()));
        try {
            dagExecutionMapper.insert(execution);
        } catch (DuplicateKeyException e) {
            // uk_dag_execution_running 部分唯一索引触发：同 DAG 已有 RUNNING
            throw new BusinessException(ErrorCode.DAG_ALREADY_RUNNING);
        }

        // 2. 预创建 node_execution（status=WAITING）— 性能2：批量插入
        List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
        if (!nodes.isEmpty()) {
            List<NodeExecution> nes = new ArrayList<>(nodes.size());
            for (DagNode node : nodes) {
                NodeExecution ne = new NodeExecution();
                ne.setId(IdWorker.getId());
                ne.setExecutionId(execution.getId());
                ne.setNodeId(node.getNodeId());
                ne.setNodeName(node.getNodeName());
                ne.setNodeType(node.getNodeType());
                ne.setStatus("WAITING");
                nes.add(ne);
            }
            nodeExecutionMapper.insertBatch(nes);
        }

        // 3. 事务提交后再调 PowerJob 触发（HTTP 调用不能放在 DB 事务里）
        //    initParams 携带 dagExecutionId，worker 节点 handler 据此对齐本地执行记录
        Long executionId = execution.getId();
        Long powerjobWorkflowId = dag.getPowerjobWorkflowId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Long wfInstanceId;
                try {
                    String initParams = JSON.toJSONString(Map.of("dagExecutionId", executionId));
                    wfInstanceId = powerJobWorkflowClient.runWorkflow(WORKER_APP_NAME, powerjobWorkflowId, initParams);
                } catch (Exception e) {
                    // 补偿：DB 已提交不能回滚，把 RUNNING 占位行标 FAILED，避免无人轮询的悬挂记录
                    String reason = "触发失败: " + e.getMessage();
                    logger.error("PowerJob 触发工作流失败，占位执行记录标为 FAILED: dagId={}, executionId={}",
                            dagId, executionId, e);
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
                        // 节点从未实际运行，标为 SKIPPED，避免历史详情里一直显示 WAITING
                        NodeExecution skipped = new NodeExecution();
                        skipped.setStatus("SKIPPED");
                        skipped.setErrorMessage("DAG 触发失败，节点未运行");
                        nodeExecutionMapper.update(skipped,
                                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NodeExecution>()
                                        .eq("execution_id", executionId)
                                        .eq("status", "WAITING"));
                    } catch (Exception ex) {
                        logger.error("补偿标记 FAILED/SKIPPED 失败，需人工处理悬挂执行记录: executionId={}", executionId, ex);
                    }
                    throw new BusinessException(ErrorCode.DS_API_ERROR, reason);
                }
                // 回写 PowerJob 工作流实例 ID，供 DagExecutionSyncService 轮询收尾
                DagExecution fresh = dagExecutionMapper.selectById(executionId);
                if (fresh != null) {
                    fresh.setPowerjobWfInstanceId(wfInstanceId);
                    dagExecutionMapper.updateById(fresh);
                }
            }
        });

        return getDetail(execution.getId());
    }

    /**
     * Sprint 3 P1-1：stop 同步把未结束子节点标 SKIPPED，避免前端看到"幽灵节点"
     */
    @Transactional
    public void stop(Long dagId, Long executionId) {
        DagExecution execution = dagExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION);
        }
        if (execution.getDagId() == null || !execution.getDagId().equals(dagId)) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION, "执行实例不属于该 DAG");
        }
        if (!"RUNNING".equalsIgnoreCase(execution.getStatus())) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION, "该执行已结束");
        }
        Dag dag = dagMapper.selectById(execution.getDagId());
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        // 清子节点（P1-1）
        LocalDateTime now = LocalDateTime.now();
        int skipped = nodeExecutionMapper.markSkippedByExecutionId(executionId, now);
        execution.setStatus("TERMINATED");
        execution.setEndTime(now);
        if (execution.getStartTime() != null) {
            execution.setDurationMs(java.time.Duration.between(execution.getStartTime(), now).toMillis());
        }
        dagExecutionMapper.updateById(execution);
        logger.info("DAG 执行停止: executionId={}, 子节点清掉 {} 个", executionId, skipped);

        // PowerJob 停止（HTTP 调用）放到事务提交后：DB 状态已落库，远端失败仅警告
        // 保留异步有限次重试：trigger 后立刻 stop 时实例可能尚未进入可停止状态（server 拒绝非运行中实例的停止）
        Long powerjobWfInstanceId = execution.getPowerjobWfInstanceId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (powerjobWfInstanceId == null) {
                    // 触发补偿行 / DS 存量执行无 PowerJob 实例，本地状态已收尾，无需远端停止
                    logger.info("执行无 PowerJob 工作流实例，跳过远端停止: executionId={}", executionId);
                    return;
                }
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    for (int attempt = 1; attempt <= PJ_STOP_MAX_ATTEMPTS; attempt++) {
                        try {
                            powerJobWorkflowClient.stopWfInstance(WORKER_APP_NAME, powerjobWfInstanceId);
                            return;
                        } catch (Exception e) {
                            if (attempt == PJ_STOP_MAX_ATTEMPTS) {
                                logger.warn("PowerJob 停止执行失败（重试 {} 次后放弃）: executionId={}",
                                        PJ_STOP_MAX_ATTEMPTS, executionId, e);
                                return;
                            }
                            try {
                                Thread.sleep(PJ_STOP_RETRY_INTERVAL_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                });
            }
        });
    }

    /** 停止的最大重试次数（覆盖实例创建后尚未进入运行态的短暂窗口） */
    private static final int PJ_STOP_MAX_ATTEMPTS = 5;
    /** 停止重试间隔（毫秒） */
    private static final long PJ_STOP_RETRY_INTERVAL_MS = 2000L;

    public DagExecutionDTO getDetail(Long executionId) {
        DagExecution execution = dagExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION, "执行实例不存在");
        }
        Dag dag = dagMapper.selectById(execution.getDagId());
        return toDTO(execution, dag);
    }

    /**
     * Sprint 4：真正的重跑失败节点（P3：映射为 PowerJob retryWfInstance）。
     * PowerJob 的重试语义是"原工作流实例就地重跑失败节点"（wfInstanceId 不变，
     * server 侧 resetRetryableNode 只重置可重试节点，成功节点结果复用），
     * 因此不再新建 dag_execution，而是把原执行记录重置回 RUNNING 续跑：
     * 失败/跳过节点重置为 WAITING，成功节点保持 SUCCESS。
     *
     * @param dagId       路径上的 dagId（与 execution 内的 dagId 必须一致）
     * @param executionId 要重跑的旧执行实例 id
     * @return 重跑中的执行实例 DTO（与旧执行同一 id）
     */
    @Transactional
    public DagExecutionDTO rerunFailed(Long dagId, Long executionId) {
        DagExecution oldExecution = dagExecutionMapper.selectById(executionId);
        if (oldExecution == null) {
            throw new BusinessException(ErrorCode.DAG_EXECUTION_NOT_FOUND,
                    "执行实例不存在: " + executionId);
        }
        if (oldExecution.getDagId() == null || !oldExecution.getDagId().equals(dagId)) {
            throw new BusinessException(ErrorCode.DAG_EXECUTION_NOT_FOUND,
                    "执行实例 " + executionId + " 不属于 DAG " + dagId);
        }
        String oldStatus = oldExecution.getStatus();
        if (!"FAILED".equalsIgnoreCase(oldStatus) && !"TERMINATED".equalsIgnoreCase(oldStatus)
                && !"SUCCESS".equalsIgnoreCase(oldStatus)) {
            throw new BusinessException(ErrorCode.DAG_RERUN_ALREADY_RUNNING,
                    "只能对终态执行记录重跑失败节点: " + oldStatus);
        }

        Long runningCount = dagExecutionMapper.selectCount(
                new QueryWrapper<DagExecution>().eq("dag_id", dagId).eq("status", "RUNNING"));
        if (runningCount != null && runningCount > 0) {
            throw new BusinessException(ErrorCode.DAG_RERUN_ALREADY_RUNNING,
                    "DAG " + dagId + " 当前正在执行中，请等待执行结束或先停止后重试");
        }

        Dag dag = dagMapper.selectById(dagId);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        if (!"ENABLED".equalsIgnoreCase(dag.getStatus())) {
            throw new BusinessException(ErrorCode.DAG_DISABLED);
        }
        if (oldExecution.getPowerjobWfInstanceId() == null) {
            // DS 存量的执行记录没有 PowerJob 工作流实例，无法就地重试
            throw new BusinessException(ErrorCode.DS_API_ERROR,
                    "该执行记录无 PowerJob 工作流实例（迁移前存量），无法重跑，请重新触发");
        }

        List<NodeExecution> oldNodes = nodeExecutionMapper.selectByExecutionId(executionId);
        List<NodeExecution> rerunNodes = oldNodes.stream()
                .filter(n -> "FAILED".equalsIgnoreCase(n.getStatus()) || "SKIPPED".equalsIgnoreCase(n.getStatus()))
                .toList();
        if (rerunNodes.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "没有需要重跑的失败/跳过节点");
        }

        // 1. 原执行记录重置回 RUNNING 续跑（wfInstanceId 不变，与 PowerJob 就地重试语义对齐）
        LocalDateTime now = LocalDateTime.now();
        oldExecution.setStatus("RUNNING");
        oldExecution.setStartTime(now);
        oldExecution.setEndTime(null);
        oldExecution.setDurationMs(null);
        oldExecution.setErrorMessage(null);
        try {
            dagExecutionMapper.updateById(oldExecution);
        } catch (DuplicateKeyException e) {
            // uk_dag_execution_running 部分唯一索引触发：并发下已有其他 RUNNING
            throw new BusinessException(ErrorCode.DAG_ALREADY_RUNNING);
        }

        // 2. 失败/跳过节点重置为 WAITING（清空上轮运行痕迹），成功节点保持 SUCCESS 复用结果
        nodeExecutionMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NodeExecution>()
                        .set("status", "WAITING")
                        .set("start_time", null)
                        .set("end_time", null)
                        .set("duration_ms", null)
                        .set("error_message", null)
                        .set("output_info", null)
                        .eq("execution_id", executionId)
                        .in("status", "FAILED", "SKIPPED"));

        // 3. 事务提交后调 PowerJob 重试（HTTP 调用不能放在 DB 事务里）
        Long wfInstanceId = oldExecution.getPowerjobWfInstanceId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    powerJobWorkflowClient.retryWfInstance(WORKER_APP_NAME, wfInstanceId);
                } catch (Exception e) {
                    String reason = "重跑触发失败: " + e.getMessage();
                    logger.error("PowerJob 重跑工作流失败: dagId={}, executionId={}, wfInstanceId={}",
                            dagId, executionId, wfInstanceId, e);
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
                        NodeExecution skipped = new NodeExecution();
                        skipped.setStatus("SKIPPED");
                        skipped.setErrorMessage("DAG 重跑触发失败，节点未运行");
                        nodeExecutionMapper.update(skipped,
                                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NodeExecution>()
                                        .eq("execution_id", executionId)
                                        .eq("status", "WAITING"));
                    } catch (Exception ex) {
                        logger.error("重跑失败补偿标记失败: executionId={}", executionId, ex);
                    }
                    throw new BusinessException(ErrorCode.DS_API_ERROR, reason);
                }
            }
        });

        return getDetail(executionId);
    }

    /**
     * Sprint 3 性能1：批量查 Dag 避免 N+1
     */
    public List<DagExecutionDTO> listByDag(Long dagId) {
        List<DagExecution> list = dagExecutionMapper.selectByDagId(dagId);
        if (list.isEmpty()) return List.of();
        // 批量查 Dag（虽然同 dagId 的 execution 都属于同一 Dag，但保持一致风格）
        Set<Long> dagIds = list.stream().map(DagExecution::getDagId).collect(Collectors.toSet());
        Map<Long, Dag> dagMap = dagMapper.selectBatchIds(dagIds).stream()
                .collect(Collectors.toMap(Dag::getId, d -> d));
        List<DagExecutionDTO> result = new ArrayList<>(list.size());
        for (DagExecution ex : list) {
            result.add(toDTO(ex, dagMap.get(ex.getDagId())));
        }
        return result;
    }

    /**
     * Sprint 3 §6.7.3：全局执行历史（多 DAG 维度）
     * <p>
     * 流程：
     * 1. 解析 ISO 时间字符串 → LocalDateTime（容器时区 Asia/Shanghai）
     * 2. 若有 dagName 模糊匹配，先查 dag 表拿 ID 集合，再 IN 过滤 dag_execution
     * 3. MyBatis-Plus 分页查 dag_execution（按 start_time DESC）
     * 4. 批量查 dag 表拿 dagName（避免 N+1）
     * 5. 单次查 node_execution 表（按 execution_id IN 列表），Java 端按 executionId+status 分组算 nodeCount/successCount/failedCount/skippedCount
     * 6. durationMs 优先取 DB 字段，未结束（endTime 为空）按当前时间实时计算
     *
     * @param filter 全局筛选条件（已通过 controller 解析为非空默认值）
     * @return 分页结果（DagExecutionGlobalDto 列表 + total）
     */
    public PageResult<DagExecutionGlobalDto> listAll(GlobalExecutionFilter filter) {
        // 1. 解析时间（null 视为无界；非法格式直接抛错）
        LocalDateTime startTimeFrom = parseIsoToLocalDateTime(filter.getStartTimeFrom(), "startTimeFrom");
        LocalDateTime startTimeTo = parseIsoToLocalDateTime(filter.getStartTimeTo(), "startTimeTo");
        if (startTimeFrom != null && startTimeTo != null && startTimeFrom.isAfter(startTimeTo)) {
            // 范围倒置：复用 INTERNAL_ERROR（与 SyncJobService.validateRequest 风格一致；本仓库无"通用 400"枚举）
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "执行时间范围非法：开始时间晚于结束时间");
        }

        // 2. dagName 模糊匹配 → dagId 集合
        Set<Long> dagIdFilter = null;
        if (StringUtils.hasText(filter.getDagName())) {
            String keyword = filter.getDagName().trim();
            List<Dag> matchedDags = dagMapper.selectList(
                    new QueryWrapper<Dag>().like("name", keyword).select("id"));
            if (matchedDags.isEmpty()) {
                // 无匹配 DAG，直接返回空页
                return PageResult.of(List.of(), 0L, filter.getPage(), filter.getPageSize());
            }
            dagIdFilter = matchedDags.stream().map(Dag::getId).collect(Collectors.toSet());
        }

        // 2.5 所属项目名称模糊匹配 → projectId → dagId 集合，再与 dagName 结果取交集
        if (StringUtils.hasText(filter.getProjectName())) {
            String keyword = filter.getProjectName().trim();
            List<DagProject> matchedProjects = dagProjectMapper.selectList(
                    new QueryWrapper<DagProject>().like("name", keyword).select("id"));
            if (matchedProjects.isEmpty()) {
                return PageResult.of(List.of(), 0L, filter.getPage(), filter.getPageSize());
            }
            Set<Long> projectIds = matchedProjects.stream().map(DagProject::getId).collect(Collectors.toSet());
            List<Dag> matchedDags = dagMapper.selectList(
                    new QueryWrapper<Dag>().in("project_id", projectIds).select("id"));
            if (matchedDags.isEmpty()) {
                return PageResult.of(List.of(), 0L, filter.getPage(), filter.getPageSize());
            }
            Set<Long> projectDagIds = matchedDags.stream().map(Dag::getId).collect(Collectors.toSet());
            if (dagIdFilter != null) {
                dagIdFilter.retainAll(projectDagIds);
                if (dagIdFilter.isEmpty()) {
                    return PageResult.of(List.of(), 0L, filter.getPage(), filter.getPageSize());
                }
            } else {
                dagIdFilter = projectDagIds;
            }
        }

        // 3. 分页查询 dag_execution
        QueryWrapper<DagExecution> wrapper = new QueryWrapper<>();
        if (filter.getDagId() != null) {
            // dagId 精确过滤（任务列表「历史」跳入）
            wrapper.eq("dag_id", filter.getDagId());
        }
        if (dagIdFilter != null) {
            wrapper.in("dag_id", dagIdFilter);
        }
        if (StringUtils.hasText(filter.getStatus())) {
            wrapper.eq("status", filter.getStatus().trim());
        }
        if (StringUtils.hasText(filter.getTriggerType())) {
            wrapper.eq("trigger_type", filter.getTriggerType().trim());
        }
        if (startTimeFrom != null) {
            wrapper.ge("start_time", startTimeFrom);
        }
        if (startTimeTo != null) {
            wrapper.lt("start_time", startTimeTo);
        }
        wrapper.orderByDesc("start_time");

        IPage<DagExecution> page = new Page<>(filter.getPage(), filter.getPageSize());
        IPage<DagExecution> result = dagExecutionMapper.selectPage(page, wrapper);
        List<DagExecution> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), result.getCurrent(), result.getSize());
        }

        // 4. 批量查 dag 拿 dagName（性能1：避免 N+1）
        Set<Long> dagIds = records.stream().map(DagExecution::getDagId).collect(Collectors.toSet());
        Map<Long, Dag> dagMap = dagMapper.selectBatchIds(dagIds).stream()
                .collect(Collectors.toMap(Dag::getId, d -> d));

        // 5. 单次查 node_execution 拿全部节点状态，Java 端分组
        List<Long> executionIds = records.stream().map(DagExecution::getId).toList();
        Map<Long, List<NodeExecution>> nodeMap = aggregateNodeDetails(executionIds);
        Map<Long, NodeStat> nodeStatMap = toNodeStatMap(nodeMap);

        // 6. 组装 DTO
        List<DagExecutionGlobalDto> dtoList = new ArrayList<>(records.size());
        for (DagExecution ex : records) {
            DagExecutionGlobalDto dto = new DagExecutionGlobalDto();
            dto.setId(ex.getId());
            dto.setDagId(ex.getDagId());
            Dag dag = dagMap.get(ex.getDagId());
            dto.setDagName(dag == null ? null : dag.getName());
            dto.setTriggerType(ex.getTriggerType());
            dto.setStatus(ex.getStatus());
            dto.setStartTime(ex.getStartTime());
            dto.setEndTime(ex.getEndTime());
            // 实时算 durationMs：已结束用 DB 字段；运行中按 now-startTime 算
            if (ex.getDurationMs() != null) {
                dto.setDurationMs(ex.getDurationMs());
            } else if (ex.getStartTime() != null) {
                LocalDateTime endRef = ex.getEndTime() != null ? ex.getEndTime() : LocalDateTime.now();
                dto.setDurationMs(Duration.between(ex.getStartTime(), endRef).toMillis());
            }
            NodeStat stat = nodeStatMap.getOrDefault(ex.getId(), NodeStat.empty());
            dto.setNodeCount(stat.total);
            dto.setSuccessCount(stat.success);
            dto.setFailedCount(stat.failed);
            dto.setSkippedCount(stat.skipped);
            dto.setNodeExecutions(
                    nodeMap.getOrDefault(ex.getId(), Collections.emptyList())
                            .stream().map(this::toNodeExecutionDTO).toList());
            dtoList.add(dto);
        }
        return PageResult.of(dtoList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 把 ISO 字符串解析为 LocalDateTime（容器时区 Asia/Shanghai）。
     * 兼容 "2026-07-28T00:00:00Z"（带 Z 的 UTC）和 "2026-07-28T00:00:00"（无时区）。
     * 返回 null 表示无界；非法格式抛 INTERNAL_ERROR（与 SyncJobService.validateRequest 风格一致）。
     */
    private static final ZoneId APP_TIME_ZONE = of("Asia/Shanghai");

    private LocalDateTime parseIsoToLocalDateTime(String iso, String fieldName) {
        if (!StringUtils.hasText(iso)) {
            return null;
        }
        String trimmed = iso.trim();
        try {
            // 先按带 offset 解析（处理 Z / +08:00 等），统一转应用时区 Asia/Shanghai
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(APP_TIME_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignore) {
            // 退化：按 LocalDateTime 解析（视为 Asia/Shanghai）
            try {
                return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "时间参数 " + fieldName + " 格式非法: " + iso + "（支持 ISO 8601，如 2026-07-28T00:00:00Z）");
            }
        }
    }

    /**
     * 查询一组 execution 下的全部 node_execution，并按 executionId 分桶。
     */
    private Map<Long, List<NodeExecution>> aggregateNodeDetails(List<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<NodeExecution> nodes = nodeExecutionMapper.selectList(
                new QueryWrapper<NodeExecution>().in("execution_id", executionIds));
        return nodes.stream().collect(Collectors.groupingBy(NodeExecution::getExecutionId));
    }

    /**
     * 把 execution -> node_execution 列表 转成 execution -> 状态统计。
     */
    private Map<Long, NodeStat> toNodeStatMap(Map<Long, List<NodeExecution>> nodeMap) {
        Map<Long, NodeStat> result = new HashMap<>(nodeMap.size());
        nodeMap.forEach((executionId, nodes) -> {
            NodeStat stat = new NodeStat();
            stat.total = nodes.size();
            for (NodeExecution ne : nodes) {
                String st = ne.getStatus();
                if ("SUCCESS".equalsIgnoreCase(st)) stat.success++;
                else if ("FAILED".equalsIgnoreCase(st)) stat.failed++;
                else if ("SKIPPED".equalsIgnoreCase(st)) stat.skipped++;
            }
            result.put(executionId, stat);
        });
        return result;
    }

    /** node_execution 状态聚合桶 */
    private static final class NodeStat {
        int total = 0;
        int success = 0;
        int failed = 0;
        int skipped = 0;

        static NodeStat empty() {
            return new NodeStat();
        }
    }

    private DagExecutionDTO toDTO(DagExecution ex, Dag dag) {
        DagExecutionDTO dto = new DagExecutionDTO();
        dto.setId(ex.getId());
        dto.setDagId(ex.getDagId());
        dto.setDagName(dag == null ? null : dag.getName());
        dto.setDsProcessInstanceId(ex.getDsProcessInstanceId());
        dto.setTriggerType(ex.getTriggerType());
        dto.setStatus(ex.getStatus());
        dto.setStartTime(ex.getStartTime());
        dto.setEndTime(ex.getEndTime());
        dto.setDurationMs(ex.getDurationMs());
        dto.setEdgeSnapshot(ex.getEdgeSnapshot());
        dto.setErrorMessage(ex.getErrorMessage());
        List<NodeExecution> neList = nodeExecutionMapper.selectByExecutionId(ex.getId());
        dto.setNodeExecutions(neList.stream().map(this::toNodeExecutionDTO).toList());
        return dto;
    }

    /**
     * SYNC 节点执行日志：按 node_execution.sync_job_history_id 读 sync_job_log。
     * 返回结构与 SyncJobService.getLogs 一致，前端复用同一日志 UI。
     * 无 history id（非 SYNC 节点 / sync 尚未收尾 / 节点不存在）时返回空列表。
     */
    public List<SyncJobLogDTO> getNodeExecutionLogs(Long nodeExecutionId) {
        NodeExecution ne = nodeExecutionMapper.selectById(nodeExecutionId);
        if (ne == null || ne.getSyncJobHistoryId() == null) {
            return List.of();
        }
        return syncJobService.getLogs(ne.getSyncJobHistoryId());
    }

    private NodeExecutionDTO toNodeExecutionDTO(NodeExecution ne) {
        NodeExecutionDTO dto = new NodeExecutionDTO();
        dto.setId(ne.getId());
        dto.setExecutionId(ne.getExecutionId());
        dto.setNodeId(ne.getNodeId());
        dto.setNodeName(ne.getNodeName());
        dto.setNodeType(ne.getNodeType());
        dto.setStatus(ne.getStatus());
        dto.setDsTaskInstanceId(ne.getDsTaskInstanceId());
        dto.setSyncJobId(ne.getSyncJobId());
        dto.setSyncJobHistoryId(ne.getSyncJobHistoryId());
        dto.setStartTime(ne.getStartTime());
        dto.setEndTime(ne.getEndTime());
        dto.setDurationMs(ne.getDurationMs());
        dto.setErrorMessage(ne.getErrorMessage());
        dto.setOutputInfo(ne.getOutputInfo());
        return dto;
    }

    private long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
