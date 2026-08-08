package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.alert.api.AlertApi;
import com.datanest.common.scheduler.PowerJobWorkflowClient;
import com.datanest.engineering.dto.DagProjectCreateRequest;
import com.datanest.engineering.dto.DagProjectDTO;
import com.datanest.engineering.dto.DagProjectUpdateRequest;
import com.datanest.common.constant.AlertConstants;
import com.datanest.engineering.entity.*;
import com.datanest.engineering.mapper.*;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DAG 项目服务
 * P3：PowerJob 无项目概念（工作流统一挂 data-nest-worker App），不再同步 DS 项目；
 * P4：dag_project.ds_project_code 旧列已随 V1.3.0 删除。
 */
@Service
public class DagProjectService {

    private static final Logger logger = LoggerFactory.getLogger(DagProjectService.class);

    private final DagProjectMapper dagProjectMapper;
    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final PowerJobWorkflowClient powerJobWorkflowClient;
    private final SystemUserApi systemUserApi;
    private final AlertApi alertApi;

    public DagProjectService(DagProjectMapper dagProjectMapper, DagMapper dagMapper,
                             DagNodeMapper dagNodeMapper, DagEdgeMapper dagEdgeMapper,
                             DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                             PowerJobWorkflowClient powerJobWorkflowClient,
                             SystemUserApi systemUserApi,
                             AlertApi alertApi) {
        this.dagProjectMapper = dagProjectMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.powerJobWorkflowClient = powerJobWorkflowClient;
        this.systemUserApi = systemUserApi;
        this.alertApi = alertApi;
    }

    /**
     * 创建项目。
     * P3：PowerJob 无项目概念，仅落 DB（原 afterCommit 调 DS 建项目并回写 ds_project_code 的逻辑已摘除）。
     */
    @Transactional
    public DagProjectDTO create(DagProjectCreateRequest request) {
        // 1. 校验名称唯一
        if (dagProjectMapper.selectByName(request.getName()) != null) {
            throw new BusinessException(ErrorCode.PROJECT_NAME_EXISTS);
        }
        // 2. 入 DB
        DagProject project = new DagProject();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setCreatedBy(currentUserId());
        project.setCreatedAt(LocalDateTime.now());
        dagProjectMapper.insert(project);
        return toDTO(project);
    }

    @Transactional
    public DagProjectDTO update(Long id, DagProjectUpdateRequest request) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }
        // PRD §6.2：编辑时项目名称不可修改
        if (!project.getName().equals(request.getName())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "项目名称不允许修改");
        }
        project.setDescription(request.getDescription());
        project.setUpdatedBy(currentUserId());
        project.setUpdatedAt(LocalDateTime.now());
        dagProjectMapper.updateById(project);
        return toDTO(project);
    }

    /**
     * Sprint 3 P2-4：删除 DataNest DagProject 时级联删除项目下所有 DAG。
     * P3：DS 项目概念已摘除；级联清理改为删除各 DAG 的 PowerJob 工作流（失败仅警告，不阻塞 DataNest 操作）。
     *
     * 事务边界：DB 删除在事务内完成；所有 PowerJob HTTP 调用放到 afterCommit，
     * 避免 DB 回滚时远端资源已被误删（不可恢复）。
     */
    @Transactional
    public void delete(Long id) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }

        // 1. 抓取 PowerJob 侧信息（提交后清理用）
        List<Dag> dags = dagMapper.selectList(new QueryWrapper<Dag>().eq("project_id", id));
        List<Long> workflowIds = dags.stream()
                .map(Dag::getPowerjobWorkflowId)
                .filter(Objects::nonNull)
                .toList();

        // 2. 级联删除项目下所有 DAG 的 DB 数据
        for (Dag dag : dags) {
            Long dagId = dag.getId();
            dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", dagId));
            dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", dagId));
            // 级联删除执行历史（与 DagService.delete 对齐：先删 node_execution 再删 dag_execution）
            List<DagExecution> executions = dagExecutionMapper.selectByDagId(dagId);
            List<Long> executionIds = executions == null ? List.of()
                    : executions.stream().map(DagExecution::getId).toList();
            if (!executionIds.isEmpty()) {
                nodeExecutionMapper.delete(new QueryWrapper<NodeExecution>().in("execution_id", executionIds));
                dagExecutionMapper.delete(new QueryWrapper<DagExecution>().eq("dag_id", dagId));
            }
            dagMapper.deleteById(dagId);
            // 微服务化改造：告警域数据（规则/配置/发送历史）改由 alert-service 远程级联清理；
            // 原来同事务，现在接受最终一致——远程失败仅记 warn，不阻断主删除流程
            cleanupAlertData(dagId, executionIds);
        }

        // 3. 删 DB 项目
        dagProjectMapper.deleteById(id);

        // 4. 事务提交后清理 PowerJob 侧工作流（失败仅警告，不抛异常）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long workflowId : workflowIds) {
                    try {
                        powerJobWorkflowClient.deleteWorkflow(WORKER_APP_NAME, workflowId);
                    } catch (Exception e) {
                        logger.warn("删除项目时 PowerJob 工作流清理失败: projectId={}, workflowId={}", id, workflowId, e);
                    }
                }
            }
        });
    }

    /** DAG 相关节点 job 与工作流统一挂在 data-nest-worker App（appId=2） */
    private static final String WORKER_APP_NAME = "data-nest-worker";

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

    public DagProjectDTO getById(Long id) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }
        DagProjectDTO dto = toDTO(project);
        fillUsernameNames(List.of(dto));
        return dto;
    }

    /**
     * 分页查询项目列表（支持名称模糊匹配），并批量补齐每个项目的 DAG 数量
     */
    public PageResult<DagProjectDTO> page(String name, long page, long pageSize) {
        QueryWrapper<DagProject> qw = new QueryWrapper<>();
        if (name != null && !name.isBlank()) {
            qw.like("name", name.trim());
        }
        qw.orderByDesc("created_at");

        Page<DagProject> mpPage = dagProjectMapper.selectPage(new Page<>(page, pageSize), qw);
        List<DagProject> projects = mpPage.getRecords();
        if (projects.isEmpty()) {
            return PageResult.of(List.of(), mpPage.getTotal(), page, pageSize);
        }

        // 性能优化：一次性批量查询当前页每个项目的 DAG 数量
        List<Long> projectIds = projects.stream().map(DagProject::getId).toList();
        Map<Long, Long> countMap = dagMapper.selectList(
                        new QueryWrapper<Dag>().in("project_id", projectIds))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Dag::getProjectId,
                        java.util.stream.Collectors.counting()));

        List<DagProjectDTO> records = projects.stream()
                .map(p -> {
                    DagProjectDTO dto = toDTO(p);
                    dto.setDagCount(countMap.getOrDefault(p.getId(), 0L));
                    return dto;
                })
                .toList();
        fillUsernameNames(records);
        return PageResult.of(records, mpPage.getTotal(), page, pageSize);
    }

    private long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private DagProjectDTO toDTO(DagProject entity) {
        DagProjectDTO dto = new DagProjectDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        return dto;
    }

    private void fillUsernameNames(List<DagProjectDTO> dtos) {
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
        for (DagProjectDTO dto : dtos) {
            if (dto.getCreatedBy() != null && dto.getCreatedBy() > 0) {
                dto.setCreatedByName(usernameMap.getOrDefault(dto.getCreatedBy(), "-"));
            }
            if (dto.getUpdatedBy() != null && dto.getUpdatedBy() > 0) {
                dto.setUpdatedByName(usernameMap.getOrDefault(dto.getUpdatedBy(), "-"));
            }
        }
    }

    /**
     * 经 system 服务 Feign 批量查询 userId → username 映射。
     * system 不可用时降级为空 Map 并记 warn（列表页名称列退化为空），不拖垮本接口。
     */
    private Map<Long, String> usernames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常（如序列化错），warn + 计数后返回空 Map
        return RemoteCalls.execute("system.usernames", () -> {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds.stream().toList());
            return result == null || result.data() == null ? Map.of() : result.data();
        }, Map.of());
    }
}
