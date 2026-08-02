package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.config.DolphinSchedulerConfig;
import com.datanest.engineering.dto.*;
import com.datanest.task.core.entity.*;
import com.datanest.task.core.mapper.*;
import com.datanest.task.core.service.DagTopologyService;
import com.datanest.task.core.service.SysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * DAG 核心服务
 * - CRUD（Project 内）
 * - 拓扑校验（环 + 孤立节点）
 * - 同步到 DS ProcessDefinition
 * - 删除同步清理
 */
@Service
public class DagService {

    private static final Logger logger = LoggerFactory.getLogger(DagService.class);

    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagTopologyService topologyService;
    private final DolphinSchedulerClient dolphinSchedulerClient;
    private final DagDsConverter dagDsConverter;
    private final DagProjectService dagProjectService;   // 用于校验项目存在（暂不直接调，留接口）
    private final DagVersionService dagVersionService;
    private final SysUserService sysUserService;

    public DagService(DagMapper dagMapper, DagNodeMapper dagNodeMapper, DagEdgeMapper dagEdgeMapper,
                      DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                      DagTopologyService topologyService, DolphinSchedulerClient dolphinSchedulerClient,
                      DagDsConverter dagDsConverter, DagProjectService dagProjectService,
                      DagVersionService dagVersionService, SysUserService sysUserService) {
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.topologyService = topologyService;
        this.dolphinSchedulerClient = dolphinSchedulerClient;
        this.dagDsConverter = dagDsConverter;
        this.dagProjectService = dagProjectService;
        this.dagVersionService = dagVersionService;
        this.sysUserService = sysUserService;
    }

    @Transactional
    public DagPayload create(DagPayload payload) {
        validateRequest(payload);

        // 1. 名称在项目内唯一
        if (dagMapper.countByProjectIdAndName(payload.getProjectId(), payload.getName()) > 0) {
            throw new BusinessException(ErrorCode.DAG_NAME_EXISTS);
        }

        // 2. 拓扑校验
        List<DagNode> nodes = toNodeEntities(payload.getNodes(), null);
        List<DagEdge> edges = toEdgeEntities(payload.getEdges(), null);
        topologyService.validateAndSort(nodes, edges);

        // 3. 入库
        Dag dag = new Dag();
        copyFromPayload(dag, payload);
        dag.setStatus(payload.getStatus() == null ? "ENABLED" : payload.getStatus());
        dag.setMaxParallelism(payload.getMaxParallelism() == null ? 3 : payload.getMaxParallelism());
        dag.setScheduleEnabled(Boolean.TRUE.equals(payload.getScheduleEnabled()) ? 1 : 0);
        dag.setReleaseState("OFFLINE");
        dag.setCreatedBy(currentUserId());
        dag.setUpdatedBy(currentUserId());
        dag.setCreatedAt(LocalDateTime.now());
        dag.setUpdatedAt(LocalDateTime.now());
        dagMapper.insert(dag);

        // 4. 保存节点 + 边（新建时无已有 code）
        Map<String, Long> codeMap = saveNodesAndEdges(dag.getId(), payload, Map.of());

        // 5. 同步到 DS（HTTP 调用不能放在 DB 事务里：事务提交后再同步，失败仅记日志，异步重试兜底）
        Long dagId = dag.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    syncToDs(dagId, payload, codeMap);
                } catch (Exception e) {
                    logger.error("DAG 创建后同步 DS 异常（不影响已提交的 DB 数据）: dagId={}", dagId, e);
                }
            }
        });

        return getDetail(dag.getId());
    }

    @Transactional
    public DagPayload update(Long id, DagPayload payload) {
        validateRequest(payload);
        Dag existing = dagMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        if (!existing.getName().equals(payload.getName())
                && dagMapper.countByProjectIdAndName(payload.getProjectId(), payload.getName()) > 0) {
            throw new BusinessException(ErrorCode.DAG_NAME_EXISTS);
        }
        // 拓扑校验
        List<DagNode> nodes = toNodeEntities(payload.getNodes(), null);
        List<DagEdge> edges = toEdgeEntities(payload.getEdges(), null);
        topologyService.validateAndSort(nodes, edges);

        copyFromPayload(existing, payload);
        existing.setStatus(payload.getStatus() == null ? existing.getStatus() : payload.getStatus());
        existing.setMaxParallelism(payload.getMaxParallelism() == null ? existing.getMaxParallelism() : payload.getMaxParallelism());
        // 首次从 MANUAL 切换为 CRON 时，如果前端未显式开启调度，默认帮用户启用，避免 CRON 不生效
        boolean becomesCron = "CRON".equalsIgnoreCase(payload.getTriggerType())
                && !"CRON".equalsIgnoreCase(existing.getTriggerType());
        if (becomesCron && !Boolean.TRUE.equals(payload.getScheduleEnabled())) {
            existing.setScheduleEnabled(1);
        } else {
            existing.setScheduleEnabled(Boolean.TRUE.equals(payload.getScheduleEnabled()) ? 1 : 0);
        }
        existing.setUpdatedBy(currentUserId());
        existing.setUpdatedAt(LocalDateTime.now());
        // 记录更新前的 DS 侧状态，用于提交后先下线再重新同步
        boolean wasOnline = "ONLINE".equalsIgnoreCase(existing.getReleaseState());
        Long dsProjectCode = existing.getDsProjectCode() == null
                ? DolphinSchedulerConfig.DEFAULT_DS_PROJECT_CODE : existing.getDsProjectCode();
        Long dsProcessDefinitionCode = existing.getDsProcessDefinitionCode();
        String dagName = existing.getName();
        dagMapper.updateById(existing);

        // 复用旧节点的 DS task code（节点重命名后保持不变）
        Map<String, Long> existingCodeMap = dagNodeMapper.selectByDagId(id).stream()
                .filter(n -> n.getNodeId() != null && n.getDsTaskCode() != null)
                .collect(Collectors.toMap(DagNode::getNodeId, DagNode::getDsTaskCode, (a, b) -> a));

        // 清空旧 nodes/edges 再插
        dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", id));
        dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", id));
        Map<String, Long> codeMap = saveNodesAndEdges(id, payload, existingCodeMap);

        // 生成版本快照（与 DB 更新在同一事务，失败则整体回滚）
        dagVersionService.createVersion(id);

        // 重新同步到 DS（HTTP 调用不能放在 DB 事务里：提交后先下线旧定义，再全量同步）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (wasOnline && dsProcessDefinitionCode != null) {
                        try {
                            dolphinSchedulerClient.releaseWorkflow(dsProjectCode,
                                    dsProcessDefinitionCode, dagName, "OFFLINE");
                        } catch (Exception e) {
                            logger.warn("DS 下线失败，继续同步: dagId={}", id, e);
                        }
                    }
                    syncToDs(id, payload, codeMap);
                } catch (Exception e) {
                    logger.error("DAG 更新后同步 DS 异常（不影响已提交的 DB 数据）: dagId={}", id, e);
                }
            }
        });

        return getDetail(id);
    }

    @Transactional
    public void delete(Long id) {
        Dag dag = dagMapper.selectById(id);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        // 先抓取 DS 侧信息，DB 提交后再做 DS 清理（HTTP 调用不能放在 DB 事务里）
        Long dsProjectCode = dag.getDsProjectCode();
        Long dsScheduleId = dag.getDsScheduleId();
        Long dsProcessDefinitionCode = dag.getDsProcessDefinitionCode();
        String releaseState = dag.getReleaseState();
        String dagName = dag.getName();

        // 1. DB 清理：级联删除 execution 及 node_execution
        List<DagExecution> executions = dagExecutionMapper.selectByDagId(id);
        if (executions != null && !executions.isEmpty()) {
            for (DagExecution execution : executions) {
                nodeExecutionMapper.delete(new QueryWrapper<NodeExecution>().eq("execution_id", execution.getId()));
            }
            dagExecutionMapper.delete(new QueryWrapper<DagExecution>().eq("dag_id", id));
            logger.info("级联删除 DAG 执行历史: dagId={}, executions={}", id, executions.size());
        }
        dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", id));
        dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", id));
        dagMapper.deleteById(id);

        // 2. DS 清理：事务提交后执行（先删定时调度，否则 schedule 孤儿会继续触发已删除的工作流；再下线 + 删除工作流）
        //    补偿：DB 已提交不能回滚，DS 清理失败时记 error 日志并抛业务异常提示用户人工清理残留
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean failed = false;
                if (dsScheduleId != null && dsProjectCode != null) {
                    try {
                        dolphinSchedulerClient.deleteSchedule(dsProjectCode, dsScheduleId);
                    } catch (Exception e) {
                        failed = true;
                        logger.error("DS 调度清理失败（DB 已删除，需人工清理 DS 残留）: dagId={}, scheduleId={}",
                                id, dsScheduleId, e);
                    }
                }
                if (dsProcessDefinitionCode != null && dsProjectCode != null) {
                    try {
                        if ("ONLINE".equalsIgnoreCase(releaseState)) {
                            dolphinSchedulerClient.releaseWorkflow(dsProjectCode,
                                    dsProcessDefinitionCode, dagName, "OFFLINE");
                        }
                        dolphinSchedulerClient.deleteWorkflow(dsProjectCode, dsProcessDefinitionCode);
                    } catch (Exception e) {
                        failed = true;
                        logger.error("DS 工作流清理失败（DB 已删除，需人工清理 DS 残留）: dagId={}, dsCode={}",
                                id, dsProcessDefinitionCode, e);
                    }
                }
                if (failed) {
                    throw new BusinessException(ErrorCode.DS_API_ERROR,
                            "DAG 已删除，但 DolphinScheduler 侧残留清理失败，请联系管理员人工清理");
                }
            }
        });
    }

    public DagPayload getDetail(Long id) {
        Dag dag = dagMapper.selectById(id);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        DagPayload dto = toPayload(dag, true);
        fillUsernameNames(List.of(dto));
        return dto;
    }

    public List<DagPayload> list(Long projectId) {
        QueryWrapper<Dag> wrapper = new QueryWrapper<>();
        if (projectId != null) wrapper.eq("project_id", projectId);
        wrapper.orderByDesc("created_at");
        List<Dag> dags = dagMapper.selectList(wrapper);
        if (dags.isEmpty()) return List.of();

        // 性能优化：批量查询 nodes；executions 用 DISTINCT ON 在 SQL 层每 dag 只取最新一条，
        // 避免把项目下全部执行历史载入内存（历史膨胀后会失控）
        List<Long> dagIds = dags.stream().map(Dag::getId).toList();
        Map<Long, List<DagNode>> nodesByDag = dagNodeMapper.selectList(
                        new QueryWrapper<com.datanest.task.core.entity.DagNode>().in("dag_id", dagIds))
                .stream().collect(Collectors.groupingBy(DagNode::getDagId));
        Map<Long, DagExecution> latestByDag = dagExecutionMapper.selectLatestByDagIds(dagIds).stream()
                .collect(Collectors.toMap(DagExecution::getDagId, e -> e));

        List<DagPayload> records = dags.stream().map(d -> {
            DagPayload dto = toPayload(d, false);
            List<DagNode> nodes = nodesByDag.getOrDefault(d.getId(), List.of());
            dto.setNodeSummary(buildNodeSummary(nodes));
            DagExecution last = latestByDag.get(d.getId());
            if (last != null) {
                DagPayload.LatestExecution le = new DagPayload.LatestExecution();
                le.setStatus(last.getStatus());
                le.setStartTime(last.getStartTime());
                le.setEndTime(last.getEndTime());
                dto.setLatestExecution(le);
            }
            return dto;
        }).toList();
        fillUsernameNames(records);
        return records;
    }

    private String buildNodeSummary(List<DagNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return "0 节点";
        long sql = nodes.stream().filter(n -> "SQL".equalsIgnoreCase(n.getNodeType())).count();
        long sync = nodes.stream().filter(n -> "SYNC".equalsIgnoreCase(n.getNodeType())).count();
        if (sql == 0) return nodes.size() + " 节点（" + sync + " 同步）";
        if (sync == 0) return nodes.size() + " 节点（" + sql + " SQL）";
        return nodes.size() + " 节点（" + sql + " SQL + " + sync + " 同步）";
    }

    /**
     * 同步 DataNest DAG 到 DS ProcessDefinition
     * 1) 如已有 ds_process_definition_code：更新；否则创建
     * 2) 发布 OFFLINE → ONLINE
     * 3) 写回 ds_process_definition_code / release_state
     *
     * Sprint 3 改进（API 测试发现）：DS 同步失败不再回滚整个事务
     * 理由：DS 端的格式校验（taskParams / taskDefinitionJson 格式）跟我们代码不同步时，
     *       不应该让用户连 DAG 都创建不了。DAG 主数据先入 DB，DS 同步作为"尽力而为"的副作用。
     *       后续通过 DagSyncService 异步重试同步。
     *
     * 注意：本方法含 DS HTTP 调用与 DB 写回，只能在事务提交后（afterCommit）以非事务方式调用，
     *       严禁在 @Transactional 方法内直接调用。
     */
    private void syncToDs(Long dagId, DagPayload payload, Map<String, Long> codeMap) {
        Dag dag = dagMapper.selectById(dagId);
        Long dsProjectCode = resolveDsProjectCode(dag);
        if (dsProjectCode == null) {
            logger.error("DS 工作流同步失败：DAG 项目不存在或缺少 dsProjectCode, dagId={}, projectId={}",
                    dagId, dag.getProjectId());
            dag.setReleaseState("OFFLINE");
            dag.setUpdatedAt(LocalDateTime.now());
            dagMapper.updateById(dag);
            return;
        }
        List<DsTaskDefinition> taskDefs = dagDsConverter.toDsTaskDefinitions(payload, codeMap);
        String locations = dagDsConverter.buildLocationsJson(payload, codeMap);
        String relations = dagDsConverter.buildTaskRelationJson(payload, codeMap);
        String globalParams = "[]";
        Integer timeout = 0;

        Long newCode = null;
        boolean isCreate = dag.getDsProcessDefinitionCode() == null;
        try {
            if (isCreate) {
                newCode = dolphinSchedulerClient.createWorkflowDefinition(
                        dsProjectCode, dag.getName(), dag.getName(),
                        taskDefs, relations, locations, globalParams, "PARALLEL", timeout);
            } else {
                // DS 工作流处于 ONLINE 时不能直接更新，需要先 OFFLINE -> update -> ONLINE
                dolphinSchedulerClient.releaseWorkflow(dsProjectCode, dag.getDsProcessDefinitionCode(), dag.getName(), "OFFLINE");
                newCode = dolphinSchedulerClient.updateWorkflowDefinition(
                        dsProjectCode, dag.getDsProcessDefinitionCode(), dag.getName(), dag.getName(),
                        taskDefs, relations, locations, globalParams, "PARALLEL", timeout);
            }
        } catch (Exception e) {
            // 不抛异常：DS 同步失败不阻塞 DAG 创建；DAG 状态保持 OFFLINE，异步重试
            logger.error("DS 工作流同步失败（不阻塞 DAG 创建）: dagId={}, dsProjectCode={}, err={}",
                    dagId, dsProjectCode, e.getMessage());
            dag.setDsProjectCode(dsProjectCode);
            dag.setReleaseState("OFFLINE");
            dag.setUpdatedAt(LocalDateTime.now());
            dagMapper.updateById(dag);
            return;
        }

        // 发布到 ONLINE
        try {
            dolphinSchedulerClient.releaseWorkflow(dsProjectCode, newCode, dag.getName(), "ONLINE");
        } catch (Exception e) {
            logger.warn("DS 工作流发布失败: dagId={}, dsCode={}, err={}", dagId, newCode, e.getMessage());
            // 发布失败：DB 标 OFFLINE，DS 端创建/更新但未上线
            dag.setDsProjectCode(dsProjectCode);
            dag.setDsProcessDefinitionCode(newCode);
            dag.setReleaseState("OFFLINE");
            dag.setUpdatedAt(LocalDateTime.now());
            dagMapper.updateById(dag);
            return;
        }

        dag.setDsProjectCode(dsProjectCode);
        dag.setDsProcessDefinitionCode(newCode);
        dag.setReleaseState("ONLINE");

        // Sprint 3 AC-8/9：Cron DAG 同步 DS 调度
        syncDsSchedule(dag, payload.getTriggerType(), payload.getCronExpression(),
                Boolean.TRUE.equals(payload.getScheduleEnabled()), dsProjectCode, newCode);

        dag.setUpdatedAt(LocalDateTime.now());
        dagMapper.updateById(dag);
    }

    /**
     * 强制把当前 DB 中的 DAG 同步到 DS 并尝试上线。
     * 用于触发前发现 release_state=OFFLINE 时自动修复，避免用户手动保存/发布。
     */
    public void syncToDs(Long dagId) {
        DagPayload payload = getDetail(dagId);
        Map<String, Long> codeMap = dagNodeMapper.selectByDagId(dagId).stream()
                .filter(n -> n.getNodeId() != null && n.getDsTaskCode() != null)
                .collect(Collectors.toMap(DagNode::getNodeId, DagNode::getDsTaskCode, (a, b) -> a));
        syncToDs(dagId, payload, codeMap);
    }

    /**
     * 启用/停用 DAG 调度（列表开关入口）。
     */
    @Transactional
    public void startSchedule(Long id) {
        toggleSchedule(id, true);
    }

    @Transactional
    public void stopSchedule(Long id) {
        toggleSchedule(id, false);
    }

    private void toggleSchedule(Long id, boolean enabled) {
        Dag dag = dagMapper.selectById(id);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        if (enabled) {
            if (!"CRON".equalsIgnoreCase(dag.getTriggerType())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "仅 Cron 任务可启用调度");
            }
            if (!StringUtils.hasText(dag.getCronExpression())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未配置 Cron 表达式");
            }
        }
        dag.setScheduleEnabled(enabled ? 1 : 0);
        dag.setUpdatedBy(currentUserId());
        dag.setUpdatedAt(LocalDateTime.now());
        dagMapper.updateById(dag);

        // DS 调度同步（HTTP 调用）放到事务提交后：避免 DB 回滚时 DS 侧产生孤儿 schedule
        String triggerType = dag.getTriggerType();
        String cronExpression = dag.getCronExpression();
        Long dsProjectCode = dag.getDsProjectCode();
        Long dsProcessDefinitionCode = dag.getDsProcessDefinitionCode();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // 重新读一行，避免用过期的内存实体回写覆盖提交后的数据
                    Dag fresh = dagMapper.selectById(id);
                    if (fresh == null) {
                        return;
                    }
                    syncDsSchedule(fresh, triggerType, cronExpression, enabled, dsProjectCode, dsProcessDefinitionCode);
                    // syncDsSchedule 会设置/清空 dsScheduleId，需要回写
                    dagMapper.updateById(fresh);
                } catch (Exception e) {
                    logger.error("DAG 调度状态同步 DS 异常（不影响已提交的 DB 数据）: dagId={}, enabled={}", id, enabled, e);
                }
            }
        });
    }

    /**
     * 同步 DS 调度状态。
     * - CRON + scheduleEnabled=true：创建/更新并上线 schedule
     * - CRON + scheduleEnabled=false 或 非 CRON：删除已有 schedule
     */
    private void syncDsSchedule(Dag dag, String triggerType, String cronExpression,
                                boolean scheduleEnabled, Long dsProjectCode, Long processDefinitionCode) {
        boolean isCron = "CRON".equalsIgnoreCase(triggerType);
        logger.info("开始同步 DS schedule: dagId={}, triggerType={}, scheduleEnabled={}, cronExpression={}, dsScheduleId={}, processDefinitionCode={}",
                dag.getId(), triggerType, scheduleEnabled, cronExpression, dag.getDsScheduleId(), processDefinitionCode);
        if (isCron && scheduleEnabled && StringUtils.hasText(cronExpression)) {
            try {
                String scheduleJson = buildDsScheduleJson(cronExpression);
                logger.info("准备创建/更新 DS schedule: dagId={}, dsProjectCode={}, processDefinitionCode={}, scheduleJson={}",
                        dag.getId(), dsProjectCode, processDefinitionCode, scheduleJson);
                Long scheduleId = dolphinSchedulerClient.createOrUpdateSchedule(
                        dsProjectCode, dag.getDsScheduleId(), processDefinitionCode, scheduleJson);
                logger.info("DS schedule 创建/更新成功: dagId={}, scheduleId={}", dag.getId(), scheduleId);
                dag.setDsScheduleId(scheduleId);
            } catch (Exception e) {
                logger.warn("DS 调度同步失败: dagId={}, cron={}", dag.getId(), cronExpression, e);
            }
        } else if (dag.getDsScheduleId() != null) {
            try {
                logger.info("准备删除 DS schedule: dagId={}, dsProjectCode={}, scheduleId={}",
                        dag.getId(), dsProjectCode, dag.getDsScheduleId());
                dolphinSchedulerClient.deleteSchedule(dsProjectCode, dag.getDsScheduleId());
                logger.info("DS schedule 删除成功: dagId={}, scheduleId={}", dag.getId(), dag.getDsScheduleId());
                dag.setDsScheduleId(null);
            } catch (Exception e) {
                logger.warn("DS 调度删除失败: dagId={}, scheduleId={}", dag.getId(), dag.getDsScheduleId(), e);
            }
        } else {
            logger.info("无需同步 DS schedule: dagId={}, 非 CRON/未启用调度/无 cron 表达式且无已有 schedule", dag.getId());
        }
    }

    private String buildDsScheduleJson(String cronExpression) {
        // DS 3.4.2 schedule 格式：{"crontab":"...","startTime":"...","endTime":"...","timezoneId":"Asia/Shanghai"}
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return String.format(
                "{\"crontab\":\"%s\",\"startTime\":\"%s\",\"endTime\":\"%s\",\"timezoneId\":\"Asia/Shanghai\"}",
                cronExpression, fmt.format(now), fmt.format(end));
    }

    // -------- helpers --------

    /**
     * 保存节点和边，并生成/复用 DS task code。
     * @return nodeId -> dsTaskCode 映射
     */
    private Map<String, Long> saveNodesAndEdges(Long dagId, DagPayload payload, Map<String, Long> existingCodeMap) {
        Map<String, Long> codeMap = dagDsConverter.generateTaskCodes(payload, existingCodeMap);

        // Sprint 3 性能2：真正的批量插入，避免 N 次 round-trip
        if (payload.getNodes() != null && !payload.getNodes().isEmpty()) {
            List<DagNode> nodes = new ArrayList<>(payload.getNodes().size());
            long uid = currentUserId();
            LocalDateTime now = LocalDateTime.now();
            for (DagNodePayload np : payload.getNodes()) {
                DagNode node = new DagNode();
                node.setId(IdWorker.getId());
                node.setDagId(dagId);
                node.setNodeId(np.getNodeId());
                node.setNodeName(np.getNodeName());
                node.setNodeType(np.getNodeType());
                node.setPositionX(np.getPositionX());
                node.setPositionY(np.getPositionY());
                node.setConfig(np.getConfig());
                node.setDsTaskCode(codeMap.get(np.getNodeId()));
                node.setCreatedBy(uid);
                node.setUpdatedBy(uid);
                node.setCreatedAt(now);
                node.setUpdatedAt(now);
                nodes.add(node);
            }
            dagNodeMapper.insertBatch(nodes);
        }
        if (payload.getEdges() != null && !payload.getEdges().isEmpty()) {
            List<DagEdge> edges = new ArrayList<>(payload.getEdges().size());
            long uid = currentUserId();
            LocalDateTime now = LocalDateTime.now();
            for (DagEdgePayload ep : payload.getEdges()) {
                DagEdge edge = new DagEdge();
                edge.setId(IdWorker.getId());
                edge.setDagId(dagId);
                edge.setEdgeId(ep.getEdgeId());
                edge.setSourceNodeId(ep.getSourceNodeId());
                edge.setTargetNodeId(ep.getTargetNodeId());
                edge.setCreatedBy(uid);
                edge.setCreatedAt(now);
                edges.add(edge);
            }
            dagEdgeMapper.insertBatch(edges);
        }
        return codeMap;
    }

    private void validateRequest(DagPayload payload) {
        if (payload.getProjectId() == null) {
            throw new BusinessException(ErrorCode.DAG_PROJECT_ID_REQUIRED);
        }
        if (!StringUtils.hasText(payload.getName())) {
            throw new BusinessException(ErrorCode.DAG_NAME_REQUIRED);
        }
        if ("CRON".equalsIgnoreCase(payload.getTriggerType()) && !StringUtils.hasText(payload.getCronExpression())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "CRON 触发方式必须填写 cronExpression");
        }
        if (StringUtils.hasText(payload.getCronExpression())) {
            try {
                CronExpression.parse(payload.getCronExpression());
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Cron 表达式非法: " + e.getMessage());
            }
        }
    }

    private void copyFromPayload(Dag dag, DagPayload payload) {
        dag.setProjectId(payload.getProjectId());
        dag.setName(payload.getName());
        dag.setTriggerType(payload.getTriggerType() == null ? "MANUAL" : payload.getTriggerType());
        dag.setCronExpression(payload.getCronExpression());
        dag.setStatus(payload.getStatus() == null ? "ENABLED" : payload.getStatus());
    }

    private List<DagNode> toNodeEntities(List<DagNodePayload> payloads, Long dagId) {
        List<DagNode> list = new ArrayList<>();
        if (payloads == null) return list;
        for (DagNodePayload np : payloads) {
            DagNode n = new DagNode();
            n.setDagId(dagId);
            n.setNodeId(np.getNodeId());
            n.setNodeName(np.getNodeName());
            n.setNodeType(np.getNodeType());
            n.setPositionX(np.getPositionX());
            n.setPositionY(np.getPositionY());
            n.setConfig(np.getConfig());
            list.add(n);
        }
        return list;
    }

    private List<DagEdge> toEdgeEntities(List<DagEdgePayload> payloads, Long dagId) {
        List<DagEdge> list = new ArrayList<>();
        if (payloads == null) return list;
        for (DagEdgePayload ep : payloads) {
            DagEdge e = new DagEdge();
            e.setDagId(dagId);
            e.setEdgeId(ep.getEdgeId());
            e.setSourceNodeId(ep.getSourceNodeId());
            e.setTargetNodeId(ep.getTargetNodeId());
            list.add(e);
        }
        return list;
    }

    public DagPayload toPayload(Dag dag, boolean withGraph) {
        DagPayload dto = new DagPayload();
        dto.setId(dag.getId());
        dto.setProjectId(dag.getProjectId());
        dto.setName(dag.getName());
        dto.setTriggerType(dag.getTriggerType());
        dto.setCronExpression(dag.getCronExpression());
        dto.setScheduleEnabled(dag.getScheduleEnabled() != null && dag.getScheduleEnabled() == 1);
        dto.setMaxParallelism(dag.getMaxParallelism());
        dto.setStatus(dag.getStatus());
        dto.setDsProjectCode(dag.getDsProjectCode());
        dto.setDsProcessDefinitionId(dag.getDsProcessDefinitionId());
        dto.setDsProcessDefinitionCode(dag.getDsProcessDefinitionCode());
        dto.setDsScheduleId(dag.getDsScheduleId());
        dto.setReleaseState(dag.getReleaseState());
        dto.setCreatedAt(dag.getCreatedAt());
        dto.setUpdatedAt(dag.getUpdatedAt());
        dto.setCreatedBy(dag.getCreatedBy());
        dto.setUpdatedBy(dag.getUpdatedBy());
        if (withGraph) {
            List<DagNode> nodes = dagNodeMapper.selectByDagId(dag.getId());
            List<DagEdge> edges = dagEdgeMapper.selectByDagId(dag.getId());
            dto.setNodes(nodes.stream().map(this::toNodePayload).toList());
            dto.setEdges(edges.stream().map(this::toEdgePayload).toList());
        }
        return dto;
    }

    private void fillUsernameNames(List<DagPayload> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        List<Long> userIds = dtos.stream()
                .flatMap(d -> java.util.stream.Stream.of(d.getCreatedBy(), d.getUpdatedBy()))
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        Map<Long, String> usernameMap = sysUserService.getUsernameMap(userIds);
        for (DagPayload dto : dtos) {
            if (dto.getCreatedBy() != null && dto.getCreatedBy() > 0) {
                dto.setCreatedByName(usernameMap.getOrDefault(dto.getCreatedBy(), "-"));
            }
            if (dto.getUpdatedBy() != null && dto.getUpdatedBy() > 0) {
                dto.setUpdatedByName(usernameMap.getOrDefault(dto.getUpdatedBy(), "-"));
            }
        }
    }

    private DagNodePayload toNodePayload(DagNode n) {
        DagNodePayload np = new DagNodePayload();
        np.setId(n.getId());
        np.setDagId(n.getDagId());
        np.setNodeId(n.getNodeId());
        np.setNodeName(n.getNodeName());
        np.setNodeType(n.getNodeType());
        np.setPositionX(n.getPositionX());
        np.setPositionY(n.getPositionY());
        np.setConfig(n.getConfig());
        return np;
    }

    private DagEdgePayload toEdgePayload(DagEdge e) {
        DagEdgePayload ep = new DagEdgePayload();
        ep.setId(e.getId());
        ep.setEdgeId(e.getEdgeId());
        ep.setSourceNodeId(e.getSourceNodeId());
        ep.setTargetNodeId(e.getTargetNodeId());
        return ep;
    }

    private long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 根据 DAG 的 projectId 解析对应的 DS project code。
     * 优先使用 dag_project 表中的真实 ds_project_code，避免使用硬编码默认值。
     */
    private Long resolveDsProjectCode(Dag dag) {
        if (dag == null || dag.getProjectId() == null) {
            return null;
        }
        try {
            DagProjectDTO project = dagProjectService.getById(dag.getProjectId());
            if (project != null && project.getDsProjectCode() != null) {
                return project.getDsProjectCode();
            }
        } catch (BusinessException e) {
            logger.warn("DAG 所属项目不存在: projectId={}, err={}", dag.getProjectId(), e.getMessage());
        }
        // 兜底：兼容历史数据
        return dag.getDsProjectCode() == null ? DolphinSchedulerConfig.DEFAULT_DS_PROJECT_CODE : dag.getDsProjectCode();
    }
}
