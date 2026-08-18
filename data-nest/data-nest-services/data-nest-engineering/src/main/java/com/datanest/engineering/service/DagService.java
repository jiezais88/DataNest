package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.json.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.alert.api.AlertApi;
import com.datanest.common.scheduler.PJDag;
import com.datanest.common.scheduler.PowerJobWorkflowClient;
import com.datanest.engineering.dto.*;
import com.datanest.governance.api.GovernanceDatasourceApi;
import com.datanest.common.constant.AlertConstants;
import com.datanest.task.core.dto.ConditionNodeConfig;
import com.datanest.task.core.support.SystemUserResolver;
import com.datanest.engineering.entity.*;
import com.datanest.engineering.mapper.*;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * DAG 核心服务
 * - CRUD（Project 内）
 * - 拓扑校验（环 + 孤立节点）
 * - 同步到 PowerJob Workflow（P3：替换 DS ProcessDefinition）
 * - 删除同步清理
 */
@Service
public class DagService {

    private static final Logger logger = LoggerFactory.getLogger(DagService.class);

    /** DAG 工作流统一挂在 data-nest-worker App（appId=2）；节点不再单独建 job，共享 worker 注册的 5 个内置节点 job */
    private static final String WORKER_APP_NAME = "data-nest-worker";

    /** 内置共享节点 jobId 缓存（节点类型 → jobId；worker 启动时注册，解析成功后基本不变；缺失不缓存，下次同步重试） */
    private final Map<String, Long> builtinNodeJobIdCache = new ConcurrentHashMap<>();

    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagTopologyService topologyService;
    private final PowerJobWorkflowClient powerJobWorkflowClient;
    private final DagPowerJobConverter dagPowerJobConverter;
    private final DagProjectService dagProjectService;   // 用于校验项目存在（暂不直接调，留接口）
    private final DagVersionService dagVersionService;
    private final SystemUserApi systemUserApi;
    private final AlertApi alertApi;
    private final GovernanceDatasourceApi governanceDatasourceApi;
    private final DagParameterMapper dagParameterMapper;
    private final ExecutionQueueService executionQueueService;
    private final DagProjectMapper dagProjectMapper;
    private final SchedulerServiceForEngineering schedulerServiceForEngineering;

    public DagService(DagMapper dagMapper, DagNodeMapper dagNodeMapper, DagEdgeMapper dagEdgeMapper,
                      DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                      DagTopologyService topologyService, PowerJobWorkflowClient powerJobWorkflowClient,
                      DagPowerJobConverter dagPowerJobConverter, DagProjectService dagProjectService,
                      DagVersionService dagVersionService, SystemUserApi systemUserApi,
                      AlertApi alertApi,
                      GovernanceDatasourceApi governanceDatasourceApi,
                      DagParameterMapper dagParameterMapper,
                      ExecutionQueueService executionQueueService,
                      DagProjectMapper dagProjectMapper,
                      SchedulerServiceForEngineering schedulerServiceForEngineering) {
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.topologyService = topologyService;
        this.powerJobWorkflowClient = powerJobWorkflowClient;
        this.dagPowerJobConverter = dagPowerJobConverter;
        this.dagProjectService = dagProjectService;
        this.dagVersionService = dagVersionService;
        this.systemUserApi = systemUserApi;
        this.alertApi = alertApi;
        this.governanceDatasourceApi = governanceDatasourceApi;
        this.dagParameterMapper = dagParameterMapper;
        this.executionQueueService = executionQueueService;
        this.dagProjectMapper = dagProjectMapper;
        this.schedulerServiceForEngineering = schedulerServiceForEngineering;
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
        dag.setCreatedAt(LocalDateTime.now());
        dagMapper.insert(dag);

        // 4. 保存节点 + 边（新建无旧节点，PowerJob 注册信息无需平移）
        saveNodesAndEdges(dag.getId(), payload, null);

        // 5. 同步到 PowerJob（HTTP 调用不能放在 DB 事务里：事务提交后再同步，失败仅记日志，触发时懒注册兜底）
        Long dagId = dag.getId();
        payload.setId(dagId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    syncToScheduler(dagId);
                    // Sprint 11 F3 方案 A：cron 注册 job 侧独立 cron job（workflow 不再挂 cron）
                    syncDagCronJob(dagId);
                } catch (Exception e) {
                    logger.error("DAG 创建后同步 PowerJob 异常（不影响已提交的 DB 数据）: dagId={}", dagId, e);
                }
            }
        });

        return getDetail(dag.getId());
    }

    @Transactional
    public DagPayload update(Long id, DagPayload payload) {
        // 回填当前 DAG id，使 validateSubDagCycle 能以当前 DAG 为锚点检测循环引用
        //（即使请求体未带 id，A→B→A 这类循环也能被阻断）
        payload.setId(id);
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
        dagMapper.updateById(existing);

        // 清空旧 nodes/edges 再插。
        // 先抓旧节点：保留节点的 powerjob_node_id 按 nodeId 平移到新行（server 侧 workflow_node_info 按 id 幂等更新）；
        // 被移除节点无需单独清理——节点不再建独立 job（共享内置 job），游离 workflow_node_info 由 saveWorkflow 服务端物理删除
        List<DagNode> oldNodes = dagNodeMapper.selectByDagId(id);
        Map<String, DagNode> oldNodeByNodeId = oldNodes.stream()
                .collect(Collectors.toMap(DagNode::getNodeId, n -> n, (a, b) -> a));
        dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", id));
        dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", id));
        saveNodesAndEdges(id, payload, oldNodeByNodeId);

        // 生成版本快照（与 DB 更新在同一事务，失败则整体回滚）
        dagVersionService.createVersion(id);

        // 重新同步到 PowerJob（HTTP 调用不能放在 DB 事务里：提交后全量覆盖式同步，
        // PowerJob saveWorkflow 带 id 即整体更新，无需 DS 那样的先下线再更新）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    syncToScheduler(id);
                    // Sprint 11 F3 方案 A：cron/启停变更同步 job 侧 cron job
                    syncDagCronJob(id);
                } catch (Exception e) {
                    logger.error("DAG 更新后同步 PowerJob 异常（不影响已提交的 DB 数据）: dagId={}", id, e);
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
        // Sprint 5：删除前校验是否被子 DAG 节点引用（PRD §7：被引用则禁止删除）
        List<Long> referencing = dagNodeMapper.selectDagIdsReferencingSubDag(buildSubDagRefPattern(id));
        referencing.removeIf(d -> Objects.equals(d, id));
        if (!referencing.isEmpty()) {
            // 返回引用 DAG 名称列表（结构化 data），前端可具体提示被哪些 DAG 引用
            List<Dag> referencingDags = dagMapper.selectBatchIds(referencing);
            List<String> referencingNames = referencingDags.stream()
                    .map(d -> d.getName() == null ? String.valueOf(d.getId()) : d.getName())
                    .toList();
            throw new BusinessException(ErrorCode.DAG_REFERENCED,
                    "该 DAG 被子 DAG 节点引用，无法删除", referencingNames);
        }
        // 先抓取 PowerJob 侧工作流 ID，DB 提交后再做清理（HTTP 调用不能放在 DB 事务里）；
        // 节点不再建独立 job（共享内置 job），无需逐个删节点 job
        Long powerjobWorkflowId = dag.getPowerjobWorkflowId();

        // 1. DB 清理：级联删除 execution 及 node_execution
        List<DagExecution> executions = dagExecutionMapper.selectByDagId(id);
        List<Long> executionIds = executions == null ? List.of()
                : executions.stream().map(DagExecution::getId).toList();
        if (!executionIds.isEmpty()) {
            nodeExecutionMapper.delete(new QueryWrapper<NodeExecution>().in("execution_id", executionIds));
            dagExecutionMapper.delete(new QueryWrapper<DagExecution>().eq("dag_id", id));
            logger.info("级联删除 DAG 执行历史: dagId={}, executions={}", id, executionIds.size());
        }
        dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", id));
        dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", id));
        // Sprint 11 收尾（2026-08-17）：级联删除 DAG 版本快照与自定义参数（此前漏删，
        // 已产生 dag_version 70 条 / dag_parameter 53 条孤儿数据）
        dagVersionService.deleteByDagId(id);
        dagParameterMapper.delete(new QueryWrapper<DagParameter>().eq("dag_id", id));
        // Sprint 6：删除 DAG 时按 dag_id 清理其产生的血缘记录（血缘是"当前加工关系"呈现，DAG 删除后成死边）
        // 微服务化 3.4：lineage_record 归治理域，经 governance 远程删除；失败经 RemoteCalls 降级
        // 记 warn，不阻断 DAG 删除（最终一致，残留由人工或后续补偿清理）
        RemoteCalls.execute("governance.lineage.deleteByDag", () -> {
            Result<Integer> result = governanceDatasourceApi.deleteLineageByDag(id);
            int lineageDeleted = result == null || result.data() == null ? 0 : result.data();
            if (lineageDeleted > 0) {
                logger.info("级联删除 DAG 血缘: dagId={}, records={}", id, lineageDeleted);
            }
        });
        dagMapper.deleteById(id);
        // 微服务化改造：告警域数据（规则/配置/发送历史）改由 alert-service 远程级联清理；
        // 原来同事务，现在接受最终一致——远程失败仅记 warn，不阻断主删除流程，残留由人工或后续补偿清理
        cleanupAlertData(id, executionIds);

        // Sprint 11 F3 方案 A：DAG 删除前捕获 job 侧 cron job ID（事务后注销）
        Long cronJobId = dag.getSchedulerJobId();

        // 2. PowerJob 清理：事务提交后删除工作流（方案 A 后 cron 不再挂 workflow，改注销独立 cron job）
        //    补偿：DB 已提交不能回滚，清理失败时记 error 日志并抛业务异常提示用户人工清理残留
        //    节点共享内置 job，随工作流删除只剩 deleteWorkflow；游离 workflow_node_info 由 server 自动清
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (powerjobWorkflowId != null) {
                    try {
                        powerJobWorkflowClient.deleteWorkflow(WORKER_APP_NAME, powerjobWorkflowId);
                    } catch (Exception e) {
                        logger.error("PowerJob 工作流清理失败（DB 已删除，需人工清理 PowerJob 残留）: dagId={}, workflowId={}",
                                id, powerjobWorkflowId, e);
                        throw new BusinessException(ErrorCode.SCHEDULER_API_ERROR,
                                "DAG 已删除，但 PowerJob 侧残留清理失败，请联系管理员人工清理");
                    }
                }
                if (cronJobId != null) {
                    try {
                        schedulerServiceForEngineering.unregisterDagCronJob(cronJobId);
                    } catch (Exception e) {
                        logger.error("DAG 删除时注销 cron job 失败（DB 已删除，需人工清理 PowerJob 残留）: dagId={}, cronJobId={}",
                                id, cronJobId, e);
                    }
                }
            }
        });
    }

    /**
     * 经 alert-service 远程级联清理 DAG 的告警域数据：告警规则（PRD §7）、dag_alert_config、
     * dag_alert_history（按 execution_id）。远程失败仅记 warn，不阻断主删除流程（最终一致）。
     */
    private void cleanupAlertData(Long dagId, List<Long> executionIds) {
        RemoteCalls.execute("alert.cleanupDagData", () -> {
            alertApi.deleteRuleByObject(AlertConstants.OBJECT_TYPE_DAG, dagId);
            alertApi.deleteDagAlertConfigByDag(dagId);
            if (!executionIds.isEmpty()) {
                alertApi.deleteDagAlertHistoriesByExecutions(executionIds);
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

    /**
     * 队列绑定 DAG 分页查询（Sprint 11 F3 队列详情抽屉）：
     * 按 queueName 精确过滤 + 多条件筛选（DAG 名/项目名模糊、状态、优先级、触发方式），
     * 批量回填项目名/用户名/最近执行/7 天执行次数（全部一次查询，避免 N+1）。
     */
    public PageResult<DagPayload> pageByQueue(String queueName, String keyword, String status,
                                              Integer priority, String triggerType,
                                              long page, long pageSize) {
        QueryWrapper<Dag> wrapper = new QueryWrapper<>();
        wrapper.eq("queue_name", queueName);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // 同时匹配 DAG 名与所属项目名：先查项目名命中的 projectId，再拼 OR 条件
            List<Long> matchedProjectIds = dagProjectMapper.selectList(
                            new QueryWrapper<DagProject>().like("name", kw))
                    .stream().map(DagProject::getId).toList();
            wrapper.and(w -> {
                w.like("name", kw);
                if (!matchedProjectIds.isEmpty()) w.or().in("project_id", matchedProjectIds);
            });
        }
        if (StringUtils.hasText(status)) wrapper.eq("status", status);
        if (priority != null) wrapper.eq("priority", priority);
        if (StringUtils.hasText(triggerType)) wrapper.eq("trigger_type", triggerType);
        wrapper.orderByDesc("created_at");

        Page<Dag> mpPage = dagMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<Dag> dags = mpPage.getRecords();
        if (dags.isEmpty()) {
            return PageResult.of(List.of(), mpPage.getTotal(), page, pageSize);
        }

        // 批量：nodes / latest execution / 项目名 / 7 天执行次数（一次查询，避免 N+1）
        List<Long> dagIds = dags.stream().map(Dag::getId).toList();
        Map<Long, List<DagNode>> nodesByDag = dagNodeMapper.selectList(
                        new QueryWrapper<DagNode>().in("dag_id", dagIds))
                .stream().collect(Collectors.groupingBy(DagNode::getDagId));
        Map<Long, DagExecution> latestByDag = dagExecutionMapper.selectLatestByDagIds(dagIds).stream()
                .collect(Collectors.toMap(DagExecution::getDagId, e -> e));
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);
        Map<Long, Long> count7dByDag = dagExecutionMapper.countByDagIdsSince(dagIds, since7d).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("dag_id")).longValue(),
                        m -> ((Number) m.get("cnt")).longValue()));

        List<Long> projectIds = dags.stream().map(Dag::getProjectId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> projectNameById = projectIds.isEmpty() ? Map.of()
                : dagProjectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(DagProject::getId, DagProject::getName));

        List<DagPayload> records = dags.stream().map(d -> {
            DagPayload dto = toPayload(d, false);
            dto.setProjectName(projectNameById.get(d.getProjectId()));
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
            dto.setExecutionCount7d(count7dByDag.getOrDefault(d.getId(), 0L));
            return dto;
        }).toList();
        fillUsernameNames(records);
        return PageResult.of(records, mpPage.getTotal(), page, pageSize);
    }

    public List<DagPayload> list(Long projectId, String queueName) {
        QueryWrapper<Dag> wrapper = new QueryWrapper<>();
        if (projectId != null) wrapper.eq("project_id", projectId);
        if (StringUtils.hasText(queueName)) wrapper.eq("queue_name", queueName);
        wrapper.orderByDesc("created_at");
        List<Dag> dags = dagMapper.selectList(wrapper);
        if (dags.isEmpty()) return List.of();

        // 性能优化：批量查询 nodes；executions 用 DISTINCT ON 在 SQL 层每 dag 只取最新一条，
        // 避免把项目下全部执行历史载入内存（历史膨胀后会失控）
        List<Long> dagIds = dags.stream().map(Dag::getId).toList();
        Map<Long, List<DagNode>> nodesByDag = dagNodeMapper.selectList(
                        new QueryWrapper<DagNode>().in("dag_id", dagIds))
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
     * 同步 DataNest DAG 到 PowerJob Workflow（P3：替换 DS ProcessDefinition 同步）
     * 1) 解析 5 个内置共享节点 jobId（固定名称经 findJobIdByName 一次性解析并缓存，
     *    任一缺失报 BusinessException「内置 DAG 节点任务未注册，请确认 worker 已启动」）
     * 2) 逐节点 saveWorkflowNode 注册/更新 server 侧工作流节点记录（dag_node.powerjob_node_id 有值带 id 更新，无则新建），回写
     *    JOB 类型节点（nodeType=1）jobId 按节点类型取内置共享 job、nodeParams 写节点身份 JSON {"dagId","nodeId","nodeType"}；
     *    同步子 DAG 节点为 NESTED_WORKFLOW 类型（nodeType=3），jobId 填子 DAG 的 powerjobWorkflowId
     * 3) 装配 PJDag（Node.nodeId=powerjob_node_id，Edge from/to 同理）后 saveWorkflow
     *    （已有 powerjob_workflow_id 则带 id 全量覆盖；CRON 配置随工作流一并下发；
     *    server 端会物理删除本工作流下游离的 workflow_node_info，被移除节点的工作流节点记录随之清理）
     * 4) 写回 powerjob_workflow_id / release_state
     *
     * 懒注册：powerjob_workflow_id 为空的 DAG 在 trigger / 启停调度 / 编辑保存时走到本方法自动注册。
     *
     * 沿用 DS 时代的容错语义（Sprint 3 改进）：同步失败不回滚事务、不向外抛错，
     * DAG 主数据先入 DB，同步作为"尽力而为"的副作用，release_state=OFFLINE 由触发时重新同步兜底。
     *
     * 注意：本方法含 PowerJob HTTP 调用与 DB 写回，只能在事务提交后（afterCommit）以非事务方式调用，
     *       严禁在 @Transactional 方法内直接调用。
     */
    public void syncToScheduler(Long dagId) {
        Dag dag = dagMapper.selectById(dagId);
        if (dag == null) {
            logger.warn("PowerJob 工作流同步跳过：DAG 不存在（可能已删除）: dagId={}", dagId);
            return;
        }
        List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
        List<DagEdge> edges = dagEdgeMapper.selectByDagId(dagId);

        Long newWorkflowId;
        try {
            // ① 解析内置共享节点 jobId（5 个固定名，一次性解析并缓存；任一缺失说明 worker 未启动注册）
            Map<String, Long> builtinJobIdByType = resolveBuiltinNodeJobIds();
            List<DagPowerJobConverter.NodeJobDef> defs = dagPowerJobConverter.toNodeJobDefs(dagId, nodes);
            Map<Long, DagNode> nodeByPk = nodes.stream()
                    .collect(Collectors.toMap(DagNode::getId, n -> n, (a, b) -> a));
            for (DagPowerJobConverter.NodeJobDef def : defs) {
                // ② 工作流节点记录注册/更新（powerjob_node_id 有值则带 id 更新，无则新建）
                //    JOB 节点 jobId=内置共享 job、nodeParams=节点身份 JSON；嵌套节点 jobId=子 DAG 工作流 ID
                Long jobId = def.nestedWorkflow() ? def.resolvedJobId()
                        : builtinJobIdByType.get(def.nodeType());
                Long pjNodeId = powerJobWorkflowClient.saveWorkflowNode(WORKER_APP_NAME, def.powerjobNodeId(),
                        def.nestedWorkflow() ? DagPowerJobConverter.PJ_NODE_TYPE_NESTED_WORKFLOW
                                : DagPowerJobConverter.PJ_NODE_TYPE_JOB,
                        jobId, def.nodeName(), def.nodeParams(), false);
                // 回写 dag_node.powerjob_node_id（显式 set 按主键定点更新，不覆盖 afterCommit 外的并发写）
                dagNodeMapper.update(null, new UpdateWrapper<DagNode>()
                        .eq("id", def.dagNodeId())
                        .set("powerjob_node_id", pjNodeId));
                // 同步到内存实体，供 buildWorkflowDag 装配 PJDag
                DagNode node = nodeByPk.get(def.dagNodeId());
                if (node != null) {
                    node.setPowerjobNodeId(pjNodeId);
                }
            }
            // ③ 装配 PJDag 并保存工作流（带 id 即全量覆盖更新；
            //    server 端 validateAndConvert2String 会物理删除本工作流下游离的工作流节点记录）
            PJDag pjDag = dagPowerJobConverter.buildWorkflowDag(nodes, edges, defs, builtinJobIdByType);
            // Sprint 11 F3 方案 A：workflow 不再挂 cron——cron 由 job 侧独立 cron job（schedulerJobId）驱动，
            // 到点调 /internal/dag/scheduled-trigger 做队列容量判定后再触发；
            // workflow 恒 enable（PowerJob 的 enable 只挡 cron 调度，不挡 runWorkflow 手动触发）
            newWorkflowId = powerJobWorkflowClient.saveWorkflow(WORKER_APP_NAME, dag.getPowerjobWorkflowId(),
                    dag.getName(), null, true, pjDag);
        } catch (Exception e) {
            // 不抛异常：PowerJob 同步失败不阻塞 DAG 创建/更新；DAG 状态保持 OFFLINE，触发时懒注册重试
            logger.error("PowerJob 工作流同步失败（不阻塞 DAG 保存）: dagId={}, err={}", dagId, e.getMessage(), e);
            dag.setReleaseState("OFFLINE");
            dag.setUpdatedAt(LocalDateTime.now());
            dagMapper.updateById(dag);
            return;
        }

        // ④ 写回工作流 ID 与发布状态
        dag.setPowerjobWorkflowId(newWorkflowId);
        dag.setReleaseState("ONLINE");
        dag.setUpdatedAt(LocalDateTime.now());
        dagMapper.updateById(dag);
    }

    /**
     * Sprint 11 F3 方案 A：存量迁移——把所有启用调度的 CRON DAG 迁移到「job 侧独立 cron job」模式。
     * <p>
     * 做法：逐 DAG 调 syncToScheduler（重同步 workflow，去掉 workflow 自带 cron，workflow 恒 enable）
     * + syncDagCronJob（注册/更新 job 侧 cron job）。已在方案 A 模式下（schedulerJobId 有值）的 DAG 会幂等更新。
     * <p>
     * 供管理员在部署方案 A 后调用一次；失败 DAG 记录并继续，返回迁移结果摘要。
     */
    public Map<String, Object> migrateCronJobs() {
        List<Dag> cronDags = dagMapper.selectList(new QueryWrapper<Dag>()
                .eq("trigger_type", "CRON")
                .eq("schedule_enabled", 1));
        int success = 0;
        int failed = 0;
        List<String> failedDags = new ArrayList<>();
        for (Dag dag : cronDags) {
            try {
                syncToScheduler(dag.getId());
                syncDagCronJob(dag.getId());
                success++;
            } catch (Exception e) {
                failed++;
                failedDags.add(dag.getName() + "(id=" + dag.getId() + ")");
                logger.error("DAG cron job 迁移失败: dagId={}, name={}, err={}", dag.getId(), dag.getName(), e.getMessage(), e);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", cronDags.size());
        result.put("success", success);
        result.put("failed", failed);
        result.put("failedDags", failedDags);
        return result;
    }

    /**
     * Sprint 11 F3 方案 A：同步 DAG 的 job 侧 cron job（注册/更新/注销）。
     * <p>
     * - CRON + scheduleEnabled + cronExpression 非空：注册（无 schedulerJobId）或更新（已有）job 侧 cron job，
     *   到点由 job 侧 DagScheduledTriggerHandler 调 /internal/dag/scheduled-trigger 做队列容量判定后触发；
     * - 非 CRON / 停调度 / 无 cron：注销既有 cron job 并清空 schedulerJobId。
     * <p>
     * 只在事务提交后（afterCommit）以非事务方式调用；失败仅记日志（cron 会在下次同步时重试注册）。
     */
    public void syncDagCronJob(Long dagId) {
        Dag dag = dagMapper.selectById(dagId);
        if (dag == null) {
            return;
        }
        boolean cron = "CRON".equalsIgnoreCase(dag.getTriggerType())
                && StringUtils.hasText(dag.getCronExpression())
                && dag.getScheduleEnabled() != null && dag.getScheduleEnabled() == 1;
        try {
            if (!cron) {
                // 注销既有 cron job（DAG 删除/转非 CRON/停调度）
                Long oldJobId = dag.getSchedulerJobId();
                if (oldJobId != null) {
                    schedulerServiceForEngineering.unregisterDagCronJob(oldJobId);
                    // 显式 set null 清空（MyBatis-Plus updateById 默认忽略 null 字段，需 UpdateWrapper.set 才能置空）
                    dagMapper.update(null, new UpdateWrapper<Dag>()
                            .eq("id", dagId)
                            .set("scheduler_job_id", null)
                            .set("updated_at", LocalDateTime.now()));
                    logger.info("DAG cron job 已注销: dagId={}, oldSchedulerJobId={}", dagId, oldJobId);
                }
                return;
            }
            String name = dag.getName();
            String cronExpression = dag.getCronExpression();
            boolean start = dag.getScheduleEnabled() == 1;
            Long oldJobId = dag.getSchedulerJobId();
            if (oldJobId != null) {
                schedulerServiceForEngineering.updateDagCronJob(oldJobId, dagId, name, cronExpression, start);
            } else {
                Long newJobId = schedulerServiceForEngineering.registerDagCronJob(dagId, name, cronExpression, start);
                dag.setSchedulerJobId(newJobId);
                dag.setUpdatedAt(LocalDateTime.now());
                dagMapper.updateById(dag);
                logger.info("DAG cron job 已注册: dagId={}, schedulerJobId={}, cron={}", dagId, newJobId, cronExpression);
            }
        } catch (Exception e) {
            logger.error("DAG cron job 同步失败（不影响 DB 主流程）: dagId={}, err={}", dagId, e.getMessage(), e);
        }
    }

    /**
     * 解析 5 个内置共享节点 job 的 jobId（节点类型 → jobId）。
     * 固定名称经 findJobIdByName 一次性解析并缓存（命中才缓存，缺失不缓存以便 worker 注册后重试）；
     * 任一缺失说明 worker 未启动完成注册，抛 BusinessException 由调用方按"同步失败"容错语义处理
     * （release_state=OFFLINE，触发时重试兜底）。
     */
    private Map<String, Long> resolveBuiltinNodeJobIds() {
        Map<String, Long> jobIdByType = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : DagPowerJobConverter.BUILTIN_JOB_NAME_BY_TYPE.entrySet()) {
            String type = entry.getKey();
            Long jobId = builtinNodeJobIdCache.get(type);
            if (jobId == null) {
                jobId = powerJobWorkflowClient.findJobIdByName(WORKER_APP_NAME, entry.getValue());
                if (jobId != null) {
                    builtinNodeJobIdCache.put(type, jobId);
                }
            }
            if (jobId == null) {
                missing.add(entry.getValue());
            } else {
                jobIdByType.put(type, jobId);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.SCHEDULER_API_ERROR,
                    "内置 DAG 节点任务未注册，请确认 worker 已启动（缺失: " + String.join("、", missing) + "）");
        }
        return jobIdByType;
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

        // Sprint 11 F3 方案 A：调度启停改为同步 job 侧 cron job（workflow 不再挂 cron，恒 enable）
        // HTTP 调用放到事务提交后：避免 DB 回滚时调度侧状态与 DB 不一致
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Dag fresh = dagMapper.selectById(id);
                    if (fresh == null) {
                        return;
                    }
                    if (fresh.getPowerjobWorkflowId() == null) {
                        // 懒注册：尚未同步过，走全量同步 + cron job 注册
                        syncToScheduler(id);
                        syncDagCronJob(id);
                        return;
                    }
                    // 启停 cron job（enable/disable 独立 cron job，等价旧的 workflow enable/disable）
                    syncDagCronJob(id);
                } catch (Exception e) {
                    logger.error("DAG 调度状态同步失败（不影响已提交的 DB 数据）: dagId={}, enabled={}", id, enabled, e);
                }
            }
        });
    }

    // -------- helpers --------

    /**
     * 保存节点和边（真正的批量插入，避免 N 次 round-trip）。
     *
     * @param oldNodeByNodeId 更新前的旧节点（nodeId → 旧行），用于把 powerjob_node_id
     *                        平移到新行（server 侧 workflow_node_info 按 id 幂等更新）；新建 DAG 传 null
     */
    private void saveNodesAndEdges(Long dagId, DagPayload payload, Map<String, DagNode> oldNodeByNodeId) {
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
                // 保留节点的 workflow_node_info 节点 ID 按 nodeId 平移（节点主键每次更新都会重建，注册 ID 不能丢）
                DagNode old = oldNodeByNodeId == null ? null : oldNodeByNodeId.get(np.getNodeId());
                if (old != null) {
                    node.setPowerjobNodeId(old.getPowerjobNodeId());
                }
                node.setCreatedBy(uid);
                node.setCreatedAt(now);
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
        // Sprint 5：节点类型与条件分支/子 DAG 配置校验
        validateNodeConfigs(payload);
    }

    /**
     * Sprint 5：校验节点类型白名单、CONDITION 分支配置、SUB_DAG 引用与循环引用。
     */
    private void validateNodeConfigs(DagPayload payload) {
        if (payload.getNodes() == null) {
            return;
        }
        Set<String> nodeIds = payload.getNodes().stream()
                .map(DagNodePayload::getNodeId)
                .collect(Collectors.toSet());
        Set<String> validTypes = Set.of("SQL", "SYNC", "PYTHON", "CONDITION", "SUB_DAG");

        for (DagNodePayload node : payload.getNodes()) {
            String nodeType = node.getNodeType() == null ? "" : node.getNodeType().toUpperCase();
            if (!validTypes.contains(nodeType)) {
                throw new BusinessException(ErrorCode.SCHEDULER_API_ERROR,
                        "非法节点类型: " + node.getNodeType() + " (nodeId=" + node.getNodeId() + ")");
            }
            if (!StringUtils.hasText(node.getConfig())) {
                continue;
            }
            try {
                tools.jackson.databind.node.ObjectNode cfg = JsonUtils.parseObject(node.getConfig());
                if ("CONDITION".equals(nodeType)) {
                    validateConditionConfig(cfg, node, nodeIds);
                } else if ("SUB_DAG".equals(nodeType)) {
                    Long subDagId = JsonUtils.getLong(cfg, "subDagId");
                    if (subDagId == null) {
                        throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND,
                                "子 DAG 节点缺少 subDagId (nodeId=" + node.getNodeId() + ")");
                    }
                    // Sprint 7 NG5：主→子参数映射校验（PRD R5）
                    validateSubDagParamMappings(cfg, node, payload.getId());
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SQL_PARSE_FAILED,
                        "节点 config JSON 解析失败 (nodeId=" + node.getNodeId() + "): " + e.getMessage());
            }
        }
        // 循环引用检测：子 DAG 不能直接/间接引用父 DAG
        validateSubDagCycle(payload);
    }

    private void validateConditionConfig(tools.jackson.databind.node.ObjectNode cfg, DagNodePayload node, Set<String> nodeIds) {
        ConditionNodeConfig config = JsonUtils.toJavaObject(cfg, ConditionNodeConfig.class);
        List<ConditionNodeConfig.ConditionBranch> branches =
                config == null ? null : config.getBranches();
        if (branches == null || branches.size() < 2) {
            throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID,
                    "条件分支节点至少需要 2 个分支 (nodeId=" + node.getNodeId() + ")");
        }
        for (ConditionNodeConfig.ConditionBranch branch : branches) {
            if (!StringUtils.hasText(branch.getBranchName())
                    || !StringUtils.hasText(branch.getExpression())
                    || !StringUtils.hasText(branch.getNextNodeId())) {
                throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID,
                        "条件分支的分支名称/表达式/下游节点不能为空 (nodeId=" + node.getNodeId() + ")");
            }
            if (!nodeIds.contains(branch.getNextNodeId())) {
                throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID,
                        "条件分支指向的节点不存在: " + branch.getNextNodeId() + " (nodeId=" + node.getNodeId() + ")");
            }
        }
    }

    /**
     * Sprint 7 NG5：校验子 DAG 参数映射（PRD R5）。
     * 规则：mainParam/subParam 必填（"${name}" 或裸名均可，归一化后判重）；
     * subParam 在映射内唯一；mainParam 必须在主 DAG 已声明参数或系统变量中——
     * 新建 DAG（payload.id 为空，参数尚未落库）跳过存在性校验。
     */
    private void validateSubDagParamMappings(tools.jackson.databind.node.ObjectNode cfg, DagNodePayload node, Long dagId) {
        tools.jackson.databind.node.ArrayNode mappings = JsonUtils.getArray(cfg, "paramMappings");
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        Set<String> declaredParams = new HashSet<>(Set.of("biz_date", "current_time", "dag_id"));
        if (dagId != null) {
            dagParameterMapper.selectByDagId(dagId).forEach(p -> declaredParams.add(p.getParamName()));
        }
        Set<String> seenSubParams = new HashSet<>();
        for (int i = 0; i < mappings.size(); i++) {
            tools.jackson.databind.node.ObjectNode mapping = JsonUtils.asObject(mappings.get(i));
            String mainKey = mapping == null ? null
                    : SubDagParamMappingResolver.normalizeParamName(JsonUtils.getString(mapping, "mainParam"));
            String subKey = mapping == null ? null
                    : SubDagParamMappingResolver.normalizeParamName(JsonUtils.getString(mapping, "subParam"));
            if (mainKey == null || subKey == null) {
                throw new BusinessException(ErrorCode.SUB_DAG_PARAM_INVALID,
                        "参数映射的主参数/子参数不能为空 (nodeId=" + node.getNodeId() + ")");
            }
            if (!seenSubParams.add(subKey)) {
                throw new BusinessException(ErrorCode.SUB_DAG_PARAM_INVALID,
                        "子 DAG 参数名在映射内重复: " + subKey + " (nodeId=" + node.getNodeId() + ")");
            }
            if (dagId != null && !declaredParams.contains(mainKey)) {
                throw new BusinessException(ErrorCode.SUB_DAG_PARAM_INVALID,
                        "主 DAG 参数不存在: " + mainKey + "（需先在主 DAG 参数中声明，或使用系统变量 biz_date/current_time）"
                                + " (nodeId=" + node.getNodeId() + ")");
            }
        }
    }

    /**
     * 子 DAG 循环引用与项目一致性检测：从当前 DAG 的每个 SUB_DAG 引用出发 DFS，
     * 若子图引用回当前 DAG、路径上任意 DAG，或子 DAG 与父 DAG 不属于同一项目，则阻断保存。
     */
    private void validateSubDagCycle(DagPayload payload) {
        Long currentDagId = payload.getId();
        Long parentProjectId = payload.getProjectId();
        Set<Long> directRefs = collectSubDagRefs(payload.getNodes());
        for (Long ref : directRefs) {
            Set<Long> path = new HashSet<>();
            if (currentDagId != null) {
                path.add(currentDagId);
            }
            dfsSubDagCycle(ref, path, currentDagId, parentProjectId);
        }
    }

    private void dfsSubDagCycle(Long subDagId, Set<Long> path, Long forbiddenDagId, Long parentProjectId) {
        if (path.contains(subDagId)) {
            throw new BusinessException(ErrorCode.SUB_DAG_CYCLE_DETECTED,
                    "子 DAG 存在循环引用: subDagId=" + subDagId);
        }
        Dag subDag = dagMapper.selectById(subDagId);
        if (subDag == null) {
            throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND, "引用的子 DAG 不存在: " + subDagId);
        }
        if (!"ENABLED".equalsIgnoreCase(subDag.getStatus())) {
            throw new BusinessException(ErrorCode.SUB_DAG_DISABLED, "子 DAG 未启用: " + subDag.getName());
        }
        if (Objects.equals(subDagId, forbiddenDagId)) {
            throw new BusinessException(ErrorCode.SUB_DAG_CYCLE_DETECTED,
                    "子 DAG 不能引用父 DAG 自身: " + subDagId);
        }
        if (parentProjectId != null && !Objects.equals(subDag.getProjectId(), parentProjectId)) {
            throw new BusinessException(ErrorCode.SUB_DAG_PROJECT_MISMATCH,
                    "子 DAG 必须与父 DAG 属于同一项目: " + subDag.getName());
        }
        path.add(subDagId);
        List<DagNode> nodes = dagNodeMapper.selectByDagId(subDagId);
        Set<Long> refs = collectSubDagRefsFromNodes(nodes);
        for (Long ref : refs) {
            dfsSubDagCycle(ref, path, forbiddenDagId, parentProjectId);
        }
        path.remove(subDagId);
    }

    private Set<Long> collectSubDagRefs(List<DagNodePayload> nodes) {
        Set<Long> refs = new HashSet<>();
        if (nodes == null) {
            return refs;
        }
        for (DagNodePayload node : nodes) {
            if (!"SUB_DAG".equalsIgnoreCase(node.getNodeType())) {
                continue;
            }
            if (!StringUtils.hasText(node.getConfig())) {
                continue;
            }
            try {
                Long subDagId = JsonUtils.getLong(JsonUtils.parseObject(node.getConfig()), "subDagId");
                if (subDagId != null) {
                    refs.add(subDagId);
                }
            } catch (Exception ignored) {
                // 配置解析失败由 validateNodeConfigs 统一抛错
            }
        }
        return refs;
    }

    private Set<Long> collectSubDagRefsFromNodes(List<DagNode> nodes) {
        Set<Long> refs = new HashSet<>();
        if (nodes == null) {
            return refs;
        }
        for (DagNode node : nodes) {
            if (!"SUB_DAG".equalsIgnoreCase(node.getNodeType())) {
                continue;
            }
            if (!StringUtils.hasText(node.getConfig())) {
                continue;
            }
            try {
                Long subDagId = JsonUtils.getLong(JsonUtils.parseObject(node.getConfig()), "subDagId");
                if (subDagId != null) {
                    refs.add(subDagId);
                }
            } catch (Exception ignored) {
                // 配置解析失败由 validateNodeConfigs 统一抛错
            }
        }
        return refs;
    }

    private void copyFromPayload(Dag dag, DagPayload payload) {
        dag.setProjectId(payload.getProjectId());
        dag.setName(payload.getName());
        dag.setTriggerType(payload.getTriggerType() == null ? "MANUAL" : payload.getTriggerType());
        dag.setCronExpression(payload.getCronExpression());
        dag.setStatus(payload.getStatus() == null ? "ENABLED" : payload.getStatus());
        // Sprint 11 F3：执行队列 + 优先级（默认 default/2）；队列存在性强校验（QU-2 双保险）
        String queueName = payload.getQueueName() == null || payload.getQueueName().isBlank()
                ? ExecutionQueueService.DEFAULT_QUEUE : payload.getQueueName().trim();
        executionQueueService.getQueueByName(queueName); // 不存在抛 EXECUTION_QUEUE_NOT_FOUND
        dag.setQueueName(queueName);
        int priority = payload.getPriority() == null ? 2 : payload.getPriority();
        if (priority < 1 || priority > 3) {
            throw new BusinessException(ErrorCode.EXECUTION_QUEUE_NAME_INVALID, "优先级仅支持 1=低/2=中/3=高");
        }
        dag.setPriority(priority);
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
        dto.setReleaseState(dag.getReleaseState());
        // Sprint 11 F3：执行队列 + 优先级回显（否则 DAG 详情/列表丢失 queueName/priority，
        // 前端 DAG 编辑器重开时下拉/选择器回显错误）
        dto.setQueueName(dag.getQueueName());
        dto.setPriority(dag.getPriority());
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
        Map<Long, String> usernameMap = usernames(userIds);
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
     * Sprint 5：构造 dag_node.config 中 subDagId 的匹配正则。
     * 兼容空格、数值/字符串格式；尾部用 (?=[,}]) 边界避免 1234 误匹配 12345。
     */
    private String buildSubDagRefPattern(Long subDagId) {
        return "\"subDagId\"\\s*:\\s*\\\"?" + subDagId + "\\\"?(?=[,}])";
    }

    /**
     * 经 system 服务 Feign 批量查询 userId → username 映射（委托 task-core SystemUserResolver）。
     * system 不可用时降级为空 Map（列表页名称列退化为空），不拖垮本接口。
     */
    private Map<Long, String> usernames(Collection<Long> userIds) {
        return SystemUserResolver.usernames(systemUserApi, userIds);
    }
}
