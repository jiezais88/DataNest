package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.dto.DagProjectCreateRequest;
import com.datanest.engineering.dto.DagProjectDTO;
import com.datanest.engineering.dto.DagProjectUpdateRequest;
import com.datanest.task.core.entity.DagProject;
import com.datanest.task.core.mapper.DagMapper;
import com.datanest.task.core.mapper.DagProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
    private final DolphinSchedulerClient dolphinSchedulerClient;

    public DagProjectService(DagProjectMapper dagProjectMapper, DagMapper dagMapper,
                             DolphinSchedulerClient dolphinSchedulerClient) {
        this.dagProjectMapper = dagProjectMapper;
        this.dagMapper = dagMapper;
        this.dolphinSchedulerClient = dolphinSchedulerClient;
    }

    @Transactional
    public DagProjectDTO create(DagProjectCreateRequest request) {
        // 1. 校验名称唯一
        if (dagProjectMapper.selectByName(request.getName()) != null) {
            throw new BusinessException(ErrorCode.PROJECT_NAME_EXISTS);
        }
        // 2. 在 DS 创建项目（P0-3）
        Long dsProjectCode = dolphinSchedulerClient.createProject(request.getName(), request.getDescription());
        // 3. 入库
        DagProject project = new DagProject();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setDsProjectCode(dsProjectCode);
        project.setCreatedBy(currentUserId());
        project.setUpdatedBy(currentUserId());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        dagProjectMapper.insert(project);
        return toDTO(project);
    }

    @Transactional
    public DagProjectDTO update(Long id, DagProjectUpdateRequest request) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }
        if (!project.getName().equals(request.getName())
                && dagProjectMapper.selectByName(request.getName()) != null) {
            throw new BusinessException(ErrorCode.PROJECT_NAME_EXISTS);
        }
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setUpdatedBy(currentUserId());
        project.setUpdatedAt(LocalDateTime.now());
        dagProjectMapper.updateById(project);
        return toDTO(project);
    }

    /**
     * Sprint 3 P2-4：删除 DataNest DagProject 时同步删 DS 项目
     * DS 删失败不回滚 DataNest DB（孤儿 DS 项目可以人工清理；DS 删失败场景不阻塞 DataNest 操作）
     */
    @Transactional
    public void delete(Long id) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }
        // 校验项目下是否还有 DAG
        long count = dagMapper.selectCount(new QueryWrapper<com.datanest.task.core.entity.Dag>()
                .eq("project_id", id));
        if (count > 0) {
            throw new BusinessException(ErrorCode.DAG_REFERENCED,
                    "项目下还有 " + count + " 个 DAG，无法删除");
        }
        // 先删 DB
        dagProjectMapper.deleteById(id);
        // 再删 DS 端（失败仅警告，不抛异常）
        if (project.getDsProjectCode() != null) {
            try {
                dolphinSchedulerClient.deleteProject(project.getDsProjectCode());
            } catch (Exception e) {
                logger.warn("DS 项目清理失败: dagProjectId={}, dsCode={}", id, project.getDsProjectCode(), e);
            }
        }
    }

    public DagProjectDTO getById(Long id) {
        DagProject project = dagProjectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 项目不存在: " + id);
        }
        return toDTO(project);
    }

    public List<DagProjectDTO> list() {
        return dagProjectMapper.selectList(new QueryWrapper<DagProject>().orderByDesc("created_at"))
                .stream().map(this::toDTO).toList();
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
