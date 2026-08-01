package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.engineering.dto.DagProjectCreateRequest;
import com.datanest.engineering.dto.DagProjectDTO;
import com.datanest.engineering.dto.DagProjectUpdateRequest;
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.DagProject;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DAG 项目服务
 * 每个项目同步对应一个 DS 项目
 *
 * Sprint 3 P0-3：调 DS API 真创建项目，存 ds_project_code
 * Sprint 3 P2-4：删 DagProject 同步删 DS 项目
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
    private final DolphinSchedulerClient dolphinSchedulerClient;

    public DagProjectService(DagProjectMapper dagProjectMapper, DagMapper dagMapper,
                             DagNodeMapper dagNodeMapper, DagEdgeMapper dagEdgeMapper,
                             DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                             DolphinSchedulerClient dolphinSchedulerClient) {
        this.dagProjectMapper = dagProjectMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dolphinSchedulerClient = dolphinSchedulerClient;
    }

    /**
     * 创建项目。
     * 顺序：先 DB 事务提交，再在 afterCommit 调 DS 建项目并回写 ds_project_code。
     * 旧顺序（先 DS 后 insert）在 insert 失败时会产生 DS 孤儿项目；
     * 新顺序下 DS 失败则补偿删除 DB 行并抛出可重试的业务异常，两边都不留孤儿。
     */
    @Transactional
    public DagProjectDTO create(DagProjectCreateRequest request) {
        // 1. 校验名称唯一
        if (dagProjectMapper.selectByName(request.getName()) != null) {
            throw new BusinessException(ErrorCode.PROJECT_NAME_EXISTS);
        }
        // 2. 先入 DB（ds_project_code 暂为 null，提交后由 DS 创建结果回写）
        DagProject project = new DagProject();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setCreatedBy(currentUserId());
        project.setUpdatedBy(currentUserId());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        dagProjectMapper.insert(project);

        // 3. 事务提交后在 DS 创建项目（P0-3）；失败则补偿删除 DB 行，报可重试错误
        Long projectId = project.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Long dsProjectCode = dolphinSchedulerClient.createProject(request.getName(), request.getDescription());
                    DagProject fresh = dagProjectMapper.selectById(projectId);
                    if (fresh != null) {
                        fresh.setDsProjectCode(dsProjectCode);
                        fresh.setUpdatedAt(LocalDateTime.now());
                        dagProjectMapper.updateById(fresh);
                    }
                } catch (Exception e) {
                    logger.error("DS 项目创建失败，补偿删除 DB 项目行: projectId={}, name={}", projectId, request.getName(), e);
                    try {
                        dagProjectMapper.deleteById(projectId);
                    } catch (Exception ex) {
                        logger.error("补偿删除 DB 项目行失败，需人工清理: projectId={}", projectId, ex);
                    }
                    throw new BusinessException(ErrorCode.DS_API_ERROR,
                            "DolphinScheduler 项目创建失败，请重试: " + e.getMessage());
                }
            }
        });
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
     * Sprint 3 P2-4：删除 DataNest DagProject 时级联删除项目下所有 DAG，并同步删 DS 项目。
     * DS 删失败不回滚 DataNest DB（孤儿 DS 项目/工作流可以人工清理；DS 删失败场景不阻塞 DataNest 操作）。
     *
     * 事务边界：DB 删除在事务内完成；所有 DS HTTP 调用放到 afterCommit，
     * 避免 DB 回滚时 DS 侧资源已被误删（不可恢复）。
     */
    @Transactional
    public void delete(Long id) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }

        // 1. 抓取 DS 侧信息（提交后清理用）
        Long dsProjectCode = project.getDsProjectCode();
        List<Dag> dags = dagMapper.selectList(new QueryWrapper<Dag>().eq("project_id", id));
        List<DagDsRef> dsRefs = dags.stream()
                .map(d -> new DagDsRef(d.getId(), d.getName(), d.getReleaseState(),
                        d.getDsScheduleId(), d.getDsProcessDefinitionCode()))
                .toList();

        // 2. 级联删除项目下所有 DAG 的 DB 数据
        for (Dag dag : dags) {
            dagNodeMapper.delete(new QueryWrapper<com.datanest.task.core.entity.DagNode>().eq("dag_id", dag.getId()));
            dagEdgeMapper.delete(new QueryWrapper<com.datanest.task.core.entity.DagEdge>().eq("dag_id", dag.getId()));
            // 级联删除执行历史（与 DagService.delete 对齐：先删 node_execution 再删 dag_execution）
            List<DagExecution> executions = dagExecutionMapper.selectByDagId(dag.getId());
            if (executions != null && !executions.isEmpty()) {
                for (DagExecution execution : executions) {
                    nodeExecutionMapper.delete(new QueryWrapper<NodeExecution>().eq("execution_id", execution.getId()));
                }
                dagExecutionMapper.delete(new QueryWrapper<DagExecution>().eq("dag_id", dag.getId()));
            }
            dagMapper.deleteById(dag.getId());
        }

        // 3. 删 DB 项目
        dagProjectMapper.deleteById(id);

        // 4. 事务提交后清理 DS 侧：先清各 DAG 的调度/工作流，再删 DS 项目（失败仅警告，不抛异常）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (DagDsRef ref : dsRefs) {
                    if (ref.dsScheduleId() != null && dsProjectCode != null) {
                        try {
                            dolphinSchedulerClient.deleteSchedule(dsProjectCode, ref.dsScheduleId());
                        } catch (Exception e) {
                            logger.warn("删除项目时 DS 调度清理失败: projectId={}, dagId={}, scheduleId={}",
                                    id, ref.dagId(), ref.dsScheduleId(), e);
                        }
                    }
                    if (ref.dsProcessDefinitionCode() != null && dsProjectCode != null) {
                        try {
                            if ("ONLINE".equalsIgnoreCase(ref.releaseState())) {
                                dolphinSchedulerClient.releaseWorkflow(dsProjectCode,
                                        ref.dsProcessDefinitionCode(), ref.dagName(), "OFFLINE");
                            }
                            dolphinSchedulerClient.deleteWorkflow(dsProjectCode, ref.dsProcessDefinitionCode());
                        } catch (Exception e) {
                            logger.warn("删除项目时 DS 工作流清理失败: projectId={}, dagId={}, dsCode={}",
                                    id, ref.dagId(), ref.dsProcessDefinitionCode(), e);
                        }
                    }
                }
                if (dsProjectCode != null) {
                    try {
                        dolphinSchedulerClient.deleteProject(dsProjectCode);
                    } catch (Exception e) {
                        logger.warn("DS 项目清理失败: dagProjectId={}, dsCode={}", id, dsProjectCode, e);
                    }
                }
            }
        });
    }

    /** 删除项目时需要延后到事务提交后清理的 DS 侧引用快照 */
    private record DagDsRef(Long dagId, String dagName, String releaseState,
                            Long dsScheduleId, Long dsProcessDefinitionCode) {
    }

    public DagProjectDTO getById(Long id) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }
        return toDTO(project);
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
        dto.setDsProjectCode(entity.getDsProjectCode());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
