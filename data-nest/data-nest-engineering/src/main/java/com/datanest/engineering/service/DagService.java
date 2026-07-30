package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.config.DolphinSchedulerConfig;
import com.datanest.engineering.dto.DagEdgePayload;
import com.datanest.engineering.dto.DagNodePayload;
import com.datanest.engineering.dto.DagPayload;
import com.datanest.engineering.dto.DsTaskDefinition;
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.entity.DagEdge;
import com.datanest.task.core.entity.DagNode;
import com.datanest.task.core.mapper.DagEdgeMapper;
import com.datanest.task.core.mapper.DagMapper;
import com.datanest.task.core.mapper.DagNodeMapper;
import com.datanest.task.core.service.DagTopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final DagTopologyService topologyService;
    private final DolphinSchedulerClient dolphinSchedulerClient;
    private final DagDsConverter dagDsConverter;
    private final DagProjectService dagProjectService;   // 用于校验项目存在（暂不直接调，留接口）

    public DagService(DagMapper dagMapper, DagNodeMapper dagNodeMapper, DagEdgeMapper dagEdgeMapper,
                      DagTopologyService topologyService, DolphinSchedulerClient dolphinSchedulerClient,
                      DagDsConverter dagDsConverter, DagProjectService dagProjectService) {
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.topologyService = topologyService;
        this.dolphinSchedulerClient = dolphinSchedulerClient;
        this.dagDsConverter = dagDsConverter;
        this.dagProjectService = dagProjectService;
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

        // 4. 保存节点 + 边
        saveNodesAndEdges(dag.getId(), payload);

        // 5. 同步到 DS
        syncToDs(dag.getId(), payload);

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
        existing.setScheduleEnabled(Boolean.TRUE.equals(payload.getScheduleEnabled()) ? 1 : 0);
        existing.setUpdatedBy(currentUserId());
        existing.setUpdatedAt(LocalDateTime.now());
        // 更新前先下线
        if ("ONLINE".equalsIgnoreCase(existing.getReleaseState())) {
            try {
                dolphinSchedulerClient.releaseWorkflow(
                        existing.getDsProjectCode() == null ? DolphinSchedulerConfig.DEFAULT_DS_PROJECT_CODE : existing.getDsProjectCode(),
                        existing.getDsProcessDefinitionCode(),
                        existing.getName(), "OFFLINE");
            } catch (Exception e) {
                logger.warn("DS 下线失败，继续更新: dagId={}", id, e);
            }
        }
        dagMapper.updateById(existing);

        // 清空旧 nodes/edges 再插
        dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", id));
        dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", id));
        saveNodesAndEdges(id, payload);

        // 重新同步到 DS
        syncToDs(id, payload);

        return getDetail(id);
    }

    @Transactional
    public void delete(Long id) {
        Dag dag = dagMapper.selectById(id);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        // 1. DS 清理（下线 + 删除）
        if (dag.getDsProcessDefinitionCode() != null && dag.getDsProjectCode() != null) {
            try {
                if ("ONLINE".equalsIgnoreCase(dag.getReleaseState())) {
                    dolphinSchedulerClient.releaseWorkflow(dag.getDsProjectCode(),
                            dag.getDsProcessDefinitionCode(), dag.getName(), "OFFLINE");
                }
                dolphinSchedulerClient.deleteWorkflow(dag.getDsProjectCode(),
                        dag.getDsProcessDefinitionCode());
            } catch (Exception e) {
                logger.warn("DS 工作流清理失败: dagId={}, dsCode={}", id, dag.getDsProcessDefinitionCode(), e);
            }
        }
        // 2. DB 清理
        dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", id));
        dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", id));
        dagMapper.deleteById(id);
    }

    public DagPayload getDetail(Long id) {
        Dag dag = dagMapper.selectById(id);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        return toPayload(dag, true);
    }

    public List<DagPayload> list(Long projectId) {
        QueryWrapper<Dag> wrapper = new QueryWrapper<>();
        if (projectId != null) wrapper.eq("project_id", projectId);
        wrapper.orderByDesc("created_at");
        return dagMapper.selectList(wrapper).stream().map(d -> toPayload(d, false)).toList();
    }

    /**
     * 同步 DataNest DAG 到 DS ProcessDefinition
     * 1) 如已有 ds_process_definition_code：更新；否则创建
     * 2) 发布 OFFLINE → ONLINE
     * 3) 写回 ds_process_definition_code / release_state
     */
    private void syncToDs(Long dagId, DagPayload payload) {
        Dag dag = dagMapper.selectById(dagId);
        Long dsProjectCode = dag.getDsProjectCode() == null ? DolphinSchedulerConfig.DEFAULT_DS_PROJECT_CODE : dag.getDsProjectCode();
        Map<String, Long> codeMap = dagDsConverter.generateTaskCodes(payload);
        List<DsTaskDefinition> taskDefs = dagDsConverter.toDsTaskDefinitions(payload, codeMap);
        String locations = dagDsConverter.buildLocationsJson(payload, codeMap);
        String relations = dagDsConverter.buildTaskRelationJson(payload, codeMap);
        String globalParams = "[]";
        Integer timeout = 0;

        Long newCode;
        try {
            if (dag.getDsProcessDefinitionCode() == null) {
                newCode = dolphinSchedulerClient.createWorkflowDefinition(
                        dsProjectCode, dag.getName(), dag.getName(),
                        taskDefs, relations, locations, globalParams, "PARALLEL", timeout);
            } else {
                newCode = dolphinSchedulerClient.updateWorkflowDefinition(
                        dsProjectCode, dag.getDsProcessDefinitionCode(), dag.getName(), dag.getName(),
                        taskDefs, relations, locations, globalParams, "PARALLEL", timeout);
            }
        } catch (Exception e) {
            logger.error("DS 工作流同步失败: dagId={}, dsProjectCode={}", dagId, dsProjectCode, e);
            throw new BusinessException(ErrorCode.DS_API_ERROR,
                    "同步到 DolphinScheduler 失败: " + e.getMessage());
        }

        // Sprint 3 P2-2：发布失败显式回滚 DB 状态为 OFFLINE
        try {
            dolphinSchedulerClient.releaseWorkflow(dsProjectCode, newCode, dag.getName(), "ONLINE");
        } catch (Exception e) {
            logger.error("DS 工作流发布失败: dagId={}, dsCode={}", dagId, newCode, e);
            // 把 DB 状态回滚到 OFFLINE，避免下次 update 时 DS 端还在线导致 offline 失败
            dag.setDsProcessDefinitionCode(newCode);
            dag.setReleaseState("OFFLINE");
            dag.setUpdatedAt(LocalDateTime.now());
            dagMapper.updateById(dag);
            throw new BusinessException(ErrorCode.DS_API_ERROR,
                    "DAG 已创建但发布失败，请重试: " + e.getMessage());
        }

        dag.setDsProjectCode(dsProjectCode);
        dag.setDsProcessDefinitionCode(newCode);
        dag.setReleaseState("ONLINE");
        dag.setUpdatedAt(LocalDateTime.now());
        dagMapper.updateById(dag);
    }

    // -------- helpers --------

    private void saveNodesAndEdges(Long dagId, DagPayload payload) {
        // Sprint 3 性能2：批量插入，避免 N 次 round-trip
        if (payload.getNodes() != null && !payload.getNodes().isEmpty()) {
            List<DagNode> nodes = new ArrayList<>(payload.getNodes().size());
            long uid = currentUserId();
            LocalDateTime now = LocalDateTime.now();
            for (DagNodePayload np : payload.getNodes()) {
                DagNode node = new DagNode();
                node.setDagId(dagId);
                node.setNodeId(np.getNodeId());
                node.setNodeName(np.getNodeName());
                node.setNodeType(np.getNodeType());
                node.setPositionX(np.getPositionX());
                node.setPositionY(np.getPositionY());
                node.setConfig(np.getConfig());
                node.setCreatedBy(uid);
                node.setUpdatedBy(uid);
                node.setCreatedAt(now);
                node.setUpdatedAt(now);
                nodes.add(node);
            }
            for (DagNode n : nodes) {
                dagNodeMapper.insert(n);
            }
        }
        if (payload.getEdges() != null && !payload.getEdges().isEmpty()) {
            List<DagEdge> edges = new ArrayList<>(payload.getEdges().size());
            long uid = currentUserId();
            LocalDateTime now = LocalDateTime.now();
            for (DagEdgePayload ep : payload.getEdges()) {
                DagEdge edge = new DagEdge();
                edge.setDagId(dagId);
                edge.setEdgeId(ep.getEdgeId());
                edge.setSourceNodeId(ep.getSourceNodeId());
                edge.setTargetNodeId(ep.getTargetNodeId());
                edge.setCreatedBy(uid);
                edge.setCreatedAt(now);
                edges.add(edge);
            }
            for (DagEdge e : edges) {
                dagEdgeMapper.insert(e);
            }
        }
    }

    private void validateRequest(DagPayload payload) {
        if (payload.getProjectId() == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "projectId 不能为空");
        }
        if (!StringUtils.hasText(payload.getName())) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "name 不能为空");
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
        if (withGraph) {
            List<DagNode> nodes = dagNodeMapper.selectByDagId(dag.getId());
            List<DagEdge> edges = dagEdgeMapper.selectByDagId(dag.getId());
            dto.setNodes(nodes.stream().map(this::toNodePayload).toList());
            dto.setEdges(edges.stream().map(this::toEdgePayload).toList());
        }
        return dto;
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
}
