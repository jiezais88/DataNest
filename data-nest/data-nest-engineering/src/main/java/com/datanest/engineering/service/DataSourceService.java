package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.dto.DataSourceReferenceDTO;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.engineering.dto.*;
import com.datanest.engineering.entity.CollectTask;
import com.datanest.engineering.entity.DataSourceConnection;
import com.datanest.engineering.mapper.CollectTaskMapper;
import com.datanest.engineering.mapper.DataSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DataSourceService {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceService.class);
    private static final String MASKED_PASSWORD = "********";
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_OFFLINE = "OFFLINE";

    private final DataSourceMapper dataSourceMapper;
    private final EncryptionConfig encryptionConfig;
    private final ConnectionTester connectionTester;
    private final CollectTaskMapper collectTaskMapper;

    public DataSourceService(DataSourceMapper dataSourceMapper, EncryptionConfig encryptionConfig,
                             ConnectionTester connectionTester, CollectTaskMapper collectTaskMapper) {
        this.dataSourceMapper = dataSourceMapper;
        this.encryptionConfig = encryptionConfig;
        this.connectionTester = connectionTester;
        this.collectTaskMapper = collectTaskMapper;
    }

    @Transactional
    public DataSourceDTO create(DataSourceCreateRequest request) {
        if (countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.DATASOURCE_NAME_EXISTS);
        }

        DataSourceConnection entity = new DataSourceConnection();
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setHost(request.getHost());
        entity.setPort(request.getPort());
        entity.setDatabaseName(request.getDatabaseName());
        entity.setSchemaName(request.getSchemaName());
        entity.setUsername(request.getUsername());
        entity.setEncryptedPassword(encryptionConfig.encrypt(request.getPassword()));
        entity.setDescription(request.getDescription());
        entity.setStatus(STATUS_NORMAL);
        entity.setCreatedBy(currentUserId());
        entity.setUpdatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        dataSourceMapper.insert(entity);
        return toDTO(entity);
    }

    @Transactional
    public DataSourceDTO update(Long id, DataSourceUpdateRequest request) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }

        entity.setType(request.getType());
        entity.setHost(request.getHost());
        entity.setPort(request.getPort());
        entity.setDatabaseName(request.getDatabaseName());
        entity.setSchemaName(request.getSchemaName());
        entity.setUsername(request.getUsername());

        if (Boolean.TRUE.equals(request.getPasswordChanged())) {
            if (request.getPassword() == null || request.getPassword().isBlank()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "修改密码时密码不能为空");
            }
            entity.setEncryptedPassword(encryptionConfig.encrypt(request.getPassword()));
        }

        entity.setDescription(request.getDescription());
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());

        dataSourceMapper.updateById(entity);
        return toDTO(entity);
    }

    @Transactional
    public void delete(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }

        List<DataSourceReferenceDTO> references = getReferences(id);
        if (!references.isEmpty()) {
            throw new BusinessException(ErrorCode.HAS_REFERENCES, "数据源被采集任务引用，无法删除", references);
        }

        dataSourceMapper.deleteById(id);
    }

    public DataSourceDTO getById(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        return toDTO(entity);
    }

    public PageResult<DataSourceDTO> list(DataSourceQueryRequest request) {
        IPage<DataSourceConnection> page = new Page<>(request.getPage(), request.getPageSize());
        QueryWrapper<DataSourceConnection> wrapper = new QueryWrapper<>();

        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.like("name", request.getKeyword().trim())
                    .or()
                    .like("host", request.getKeyword().trim());
        }
        if (request.getType() != null && !request.getType().isBlank()) {
            wrapper.eq("type", request.getType());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            wrapper.eq("status", request.getStatus());
        }

        wrapper.orderByDesc("created_at");
        IPage<DataSourceConnection> result = dataSourceMapper.selectPage(page, wrapper);

        List<DataSourceDTO> records = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public TestConnectionResult testConnection(TestConnectionRequest request) {
        return connectionTester.test(request);
    }

    @Transactional
    public TestConnectionResult testAndUpdateStatus(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }

        TestConnectionRequest request = new TestConnectionRequest();
        request.setType(entity.getType());
        request.setHost(entity.getHost());
        request.setPort(entity.getPort());
        request.setDatabaseName(entity.getDatabaseName());
        request.setSchemaName(entity.getSchemaName());
        request.setUsername(entity.getUsername());
        request.setPassword(encryptionConfig.decrypt(entity.getEncryptedPassword()));

        TestConnectionResult result = connectionTester.test(request);
        updateStatus(entity, result);
        return result;
    }

    public List<String> getSchemas(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        String password = encryptionConfig.decrypt(entity.getEncryptedPassword());
        return connectionTester.extractSchemas(entity, password);
    }

    public List<DataSourceReferenceDTO> getReferences(Long id) {
        List<CollectTask> tasks = collectTaskMapper.selectActiveByDatasourceId(id);
        return tasks.stream()
                .map(task -> {
                    DataSourceReferenceDTO dto = new DataSourceReferenceDTO();
                    dto.setTaskId(task.getId());
                    dto.setTaskName(task.getName());
                    dto.setStatus(task.getStatus());
                    return dto;
                })
                .toList();
    }

    public void refreshAllStatuses() {
        List<DataSourceConnection> list = dataSourceMapper.selectList(new QueryWrapper<DataSourceConnection>()
                .in("status", STATUS_NORMAL, STATUS_ERROR));

        for (DataSourceConnection entity : list) {
            try {
                TestConnectionResult result = testAndUpdateStatus(entity.getId());
                logger.info("Refreshed data source status: id={}, name={}, success={}",
                        entity.getId(), entity.getName(), result.isSuccess());
            } catch (Exception e) {
                logger.error("Failed to refresh data source status: id={}, name={}", entity.getId(), entity.getName(), e);
                entity.setStatus(STATUS_ERROR);
                entity.setErrorMessage("定时刷新异常: " + e.getMessage());
                entity.setLastTestTime(LocalDateTime.now());
                dataSourceMapper.updateById(entity);
            }
        }
    }

    private void updateStatus(DataSourceConnection entity, TestConnectionResult result) {
        entity.setStatus(result.isSuccess() ? STATUS_NORMAL : STATUS_ERROR);
        entity.setErrorMessage(result.isSuccess() ? null : result.getMessage());
        entity.setLastTestTime(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        dataSourceMapper.updateById(entity);
    }

    private DataSourceDTO toDTO(DataSourceConnection entity) {
        DataSourceDTO dto = new DataSourceDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setType(entity.getType());
        dto.setHost(entity.getHost());
        dto.setPort(entity.getPort());
        dto.setDatabaseName(entity.getDatabaseName());
        dto.setSchemaName(entity.getSchemaName());
        dto.setUsername(entity.getUsername());
        dto.setPasswordMasked(MASKED_PASSWORD);
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setLastTestTime(entity.getLastTestTime());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private long countByName(String name) {
        return dataSourceMapper.selectCount(new QueryWrapper<DataSourceConnection>().eq("name", name));
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }
}
