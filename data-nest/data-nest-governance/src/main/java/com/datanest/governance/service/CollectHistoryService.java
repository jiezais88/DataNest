package com.datanest.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.governance.dto.CollectExecutionLogDTO;
import com.datanest.governance.dto.CollectHistoryDTO;
import com.datanest.governance.dto.CollectHistoryQueryRequest;
import com.datanest.governance.entity.CollectExecutionLog;
import com.datanest.governance.entity.CollectHistory;
import com.datanest.governance.mapper.CollectExecutionLogMapper;
import com.datanest.governance.mapper.CollectHistoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CollectHistoryService {

    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper logMapper;

    public CollectHistoryService(CollectHistoryMapper collectHistoryMapper,
                                 CollectExecutionLogMapper logMapper) {
        this.collectHistoryMapper = collectHistoryMapper;
        this.logMapper = logMapper;
    }

    public PageResult<CollectHistoryDTO> list(CollectHistoryQueryRequest request) {
        IPage<CollectHistory> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<CollectHistory> wrapper = new QueryWrapper<>();
        if (request.getTaskId() != null) {
            wrapper.eq("task_id", request.getTaskId());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            wrapper.eq("status", request.getStatus());
        }
        wrapper.orderByDesc("started_at");

        IPage<CollectHistory> result = collectHistoryMapper.selectPage(page, wrapper);
        List<CollectHistoryDTO> records = result.getRecords().stream()
                .map(this::toHistoryDTO)
                .toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public CollectHistoryDTO getById(Long historyId) {
        CollectHistory entity = collectHistoryMapper.selectById(historyId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_FOUND);
        }
        return toHistoryDTO(entity);
    }

    public List<CollectExecutionLogDTO> getLogs(Long historyId) {
        CollectHistory history = collectHistoryMapper.selectById(historyId);
        if (history == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_FOUND);
        }
        List<CollectExecutionLog> logs = logMapper.selectList(
                new QueryWrapper<CollectExecutionLog>().eq("history_id", historyId).orderByAsc("created_at"));
        return logs.stream().map(this::toLogDTO).toList();
    }

    private CollectExecutionLogDTO toLogDTO(CollectExecutionLog entity) {
        CollectExecutionLogDTO dto = new CollectExecutionLogDTO();
        dto.setId(entity.getId());
        dto.setHistoryId(entity.getHistoryId());
        dto.setTaskId(entity.getTaskId());
        dto.setLevel(entity.getLevel());
        dto.setMessage(entity.getMessage());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private CollectHistoryDTO toHistoryDTO(CollectHistory entity) {
        CollectHistoryDTO dto = new CollectHistoryDTO();
        dto.setId(entity.getId());
        dto.setTaskId(entity.getTaskId());
        dto.setTaskName(entity.getTaskName());
        dto.setDatasourceId(entity.getDatasourceId());
        dto.setTriggerType(entity.getTriggerType());
        dto.setStatus(entity.getStatus());
        dto.setStartedAt(entity.getStartedAt());
        dto.setEndedAt(entity.getEndedAt());
        dto.setDurationMs(entity.getDurationMs());
        dto.setDbCount(entity.getDbCount());
        dto.setTableCount(entity.getTableCount());
        dto.setColumnCount(entity.getColumnCount());
        dto.setAddedTableCount(entity.getAddedTableCount());
        dto.setUpdatedTableCount(entity.getUpdatedTableCount());
        dto.setDeletedTableCount(entity.getDeletedTableCount());
        dto.setAddedColumnCount(entity.getAddedColumnCount());
        dto.setUpdatedColumnCount(entity.getUpdatedColumnCount());
        dto.setDeletedColumnCount(entity.getDeletedColumnCount());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
