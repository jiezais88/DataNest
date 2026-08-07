package com.datanest.engineering.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanest.common.constant.DataSourceStatus;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.DataSourceStatusUpdateRequest;
import com.datanest.engineering.entity.DataSourceConnection;
import com.datanest.engineering.mapper.DataSourceConnectionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据源域内部接口服务（engineering 归属表 datasource_connection）。
 */
@Service
public class InternalDatasourceService {

    private final DataSourceConnectionMapper dataSourceConnectionMapper;

    public InternalDatasourceService(DataSourceConnectionMapper dataSourceConnectionMapper) {
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
    }

    public DataSourceInfo getById(Long id) {
        return toInfo(dataSourceConnectionMapper.selectById(id));
    }

    public Map<Long, DataSourceInfo> batchGet(List<Long> ids) {
        Map<Long, DataSourceInfo> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        List<Long> valid = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (valid.isEmpty()) {
            return result;
        }
        for (DataSourceConnection entity : dataSourceConnectionMapper.selectBatchIds(valid)) {
            result.put(entity.getId(), toInfo(entity));
        }
        return result;
    }

    /** 活跃数据源（status IN NORMAL/ERROR，与 DataSourceRefreshService.refreshAllStatuses 口径一致） */
    public List<DataSourceInfo> listActive() {
        return dataSourceConnectionMapper.selectList(new QueryWrapper<DataSourceConnection>()
                        .in("status", DataSourceStatus.NORMAL.getCode(), DataSourceStatus.ERROR.getCode()))
                .stream().map(InternalDatasourceService::toInfo).toList();
    }

    public void updateStatus(Long id, DataSourceStatusUpdateRequest request) {
        DataSourceConnection entity = dataSourceConnectionMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        dataSourceConnectionMapper.update(null, new UpdateWrapper<DataSourceConnection>()
                .set("status", request.getStatus())
                .set("error_message", request.getErrorMessage())
                .set("last_test_time", request.getLastTestTime())
                .set("updated_at", LocalDateTime.now())
                .eq("id", id));
    }

    static DataSourceInfo toInfo(DataSourceConnection entity) {
        if (entity == null) {
            return null;
        }
        DataSourceInfo info = new DataSourceInfo();
        info.setId(entity.getId());
        info.setName(entity.getName());
        info.setType(entity.getType());
        info.setHost(entity.getHost());
        info.setPort(entity.getPort());
        info.setDatabaseName(entity.getDatabaseName());
        info.setSchemaName(entity.getSchemaName());
        info.setUsername(entity.getUsername());
        info.setEncryptedPassword(entity.getEncryptedPassword());
        info.setDescription(entity.getDescription());
        info.setStatus(entity.getStatus());
        info.setAutoCollectOnSave(entity.getAutoCollectOnSave());
        info.setLastTestTime(entity.getLastTestTime());
        info.setErrorMessage(entity.getErrorMessage());
        info.setCreatedBy(entity.getCreatedBy());
        info.setUpdatedBy(entity.getUpdatedBy());
        info.setCreatedAt(entity.getCreatedAt());
        info.setUpdatedAt(entity.getUpdatedAt());
        return info;
    }
}
