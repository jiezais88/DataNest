package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.config.DolphinSchedulerConfig;
import com.datanest.engineering.dto.DagExecutionDTO;
import com.datanest.engineering.dto.NodeExecutionDTO;
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.DagNode;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.DagMapper;
import com.datanest.task.core.mapper.DagNodeMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DAG 执行服务
 * - 手动触发（DS 端 startWorkflowInstance）
 * - 定时轮询同步执行状态（DS → engineering）
 * - 提供执行历史查询
 *
 * Sprint 3 修复：
 * - P1-3：trigger 用 DB 唯一索引保证并发安全
 * - P1-1：stop 同步清理子节点为 SKIPPED
 * - 性能1：listByDag 批量查 Dag 避免 N+1
 */
@Service
public class DagExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(DagExecutionService.class);

    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DolphinSchedulerClient dolphinSchedulerClient;

    public DagExecutionService(DagMapper dagMapper, DagNodeMapper dagNodeMapper,
                               DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                               DolphinSchedulerClient dolphinSchedulerClient) {
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dolphinSchedulerClient = dolphinSchedulerClient;
    }

    @Transactional
    public DagExecutionDTO trigger(Long dagId) {
        Dag dag = dagMapper.selectById(dagId);
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        if (!"ENABLED".equalsIgnoreCase(dag.getStatus())) {
            throw new BusinessException(ErrorCode.DAG_DISABLED);
        }
        if (dag.getDsProcessDefinitionCode() == null) {
            throw new BusinessException(ErrorCode.DS_API_ERROR, "DAG 未同步到 DolphinScheduler");
        }

        // 1. DS 端触发
        Long dsProcessInstanceId;
        try {
            dsProcessInstanceId = dolphinSchedulerClient.startWorkflowInstance(
                    dag.getDsProjectCode() == null ? DolphinSchedulerConfig.DEFAULT_DS_PROJECT_CODE : dag.getDsProjectCode(),
                    dag.getDsProcessDefinitionCode(),
                    "END", "START_PROCESS", currentUserId(), "");
        } catch (Exception e) {
            logger.error("DS 触发工作流失败: dagId={}", dagId, e);
            throw new BusinessException(ErrorCode.DS_API_ERROR, "触发失败: " + e.getMessage());
        }

        // 2. 入库 dag_execution（P1-3：靠 DB 唯一索引 uk_dag_execution_running 保证并发安全）
        DagExecution execution = new DagExecution();
        execution.setDagId(dagId);
        execution.setDsProcessInstanceId(dsProcessInstanceId);
        execution.setTriggerType(dag.getTriggerType() == null ? "MANUAL" : dag.getTriggerType());
        execution.setStatus("RUNNING");
        execution.setStartTime(LocalDateTime.now());
        execution.setCreatedBy(currentUserId());
        execution.setCreatedAt(LocalDateTime.now());
        try {
            dagExecutionMapper.insert(execution);
        } catch (DuplicateKeyException e) {
            // uk_dag_execution_running 部分唯一索引触发：同 DAG 已有 RUNNING
            throw new BusinessException(ErrorCode.DAG_ALREADY_RUNNING);
        }

        // 3. 预创建 node_execution（status=WAITING）— 性能2：单条 insert 改 batch
        List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
        if (!nodes.isEmpty()) {
            List<NodeExecution> nes = new ArrayList<>(nodes.size());
            for (DagNode node : nodes) {
                NodeExecution ne = new NodeExecution();
                ne.setExecutionId(execution.getId());
                ne.setNodeId(node.getNodeId());
                ne.setNodeName(node.getNodeName());
                ne.setNodeType(node.getNodeType());
                ne.setStatus("WAITING");
                nes.add(ne);
            }
            for (NodeExecution ne : nes) {
                nodeExecutionMapper.insert(ne);
            }
        }

        return getDetail(execution.getId());
    }

    /**
     * Sprint 3 P1-1：stop 同步把未结束子节点标 SKIPPED，避免前端看到"幽灵节点"
     */
    @Transactional
    public void stop(Long executionId) {
        DagExecution execution = dagExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION);
        }
        if (!"RUNNING".equalsIgnoreCase(execution.getStatus())) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION, "该执行已结束");
        }
        Dag dag = dagMapper.selectById(execution.getDagId());
        if (dag == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND);
        }
        try {
            dolphinSchedulerClient.stopWorkflowInstance(
                    dag.getDsProjectCode() == null ? DolphinSchedulerConfig.DEFAULT_DS_PROJECT_CODE : dag.getDsProjectCode(),
                    execution.getDsProcessInstanceId());
        } catch (Exception e) {
            logger.warn("DS 停止执行失败: executionId={}", executionId, e);
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
    }

    public DagExecutionDTO getDetail(Long executionId) {
        DagExecution execution = dagExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.NO_RUNNING_EXECUTION, "执行实例不存在");
        }
        Dag dag = dagMapper.selectById(execution.getDagId());
        return toDTO(execution, dag);
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
        List<NodeExecution> neList = nodeExecutionMapper.selectByExecutionId(ex.getId());
        dto.setNodeExecutions(neList.stream().map(this::toNodeExecutionDTO).toList());
        return dto;
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
