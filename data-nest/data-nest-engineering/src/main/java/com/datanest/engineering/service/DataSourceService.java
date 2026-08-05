package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.*;
import com.datanest.common.dto.DataSourceReferenceDTO;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.engineering.dto.DataSourceCreateRequest;
import com.datanest.engineering.dto.DataSourceDTO;
import com.datanest.engineering.dto.DataSourceQueryRequest;
import com.datanest.engineering.dto.DataSourceUpdateRequest;
import com.datanest.task.core.dto.TestConnectionRequest;
import com.datanest.task.core.dto.TestConnectionResult;
import com.datanest.task.core.entity.CollectTask;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.mapper.*;
import com.datanest.task.core.service.ConnectionTester;
import com.datanest.task.core.service.DataSourceRefreshService;
import com.datanest.task.core.service.SysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class DataSourceService {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceService.class);
    private static final String MASKED_PASSWORD = "********";
    private static final String COLLECT_TASK_HANDLER = "collectTaskHandler";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Value("${datanest.engineering.auto-collect.worker-appname:data-nest-worker}")
    private String workerExecutorAppName;

    private final DataSourceConnectionMapper dataSourceMapper;
    private final EncryptionConfig encryptionConfig;
    private final ConnectionTester connectionTester;
    private final CollectTaskMapper collectTaskMapper;
    private final SyncJobMapper syncJobMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final ComplianceCleanupMapper complianceCleanupMapper;
    private final SchedulerClient schedulerClient;
    private final DataSourceRefreshService dataSourceRefreshService;
    private final SysUserService sysUserService;

    public DataSourceService(DataSourceConnectionMapper dataSourceMapper, EncryptionConfig encryptionConfig,
                             ConnectionTester connectionTester, CollectTaskMapper collectTaskMapper,
                             SyncJobMapper syncJobMapper,
                             MetadataTableMapper metadataTableMapper, MetadataColumnMapper metadataColumnMapper,
                             ComplianceCleanupMapper complianceCleanupMapper,
                             SchedulerClient schedulerClient, DataSourceRefreshService dataSourceRefreshService,
                             SysUserService sysUserService) {
        this.dataSourceMapper = dataSourceMapper;
        this.encryptionConfig = encryptionConfig;
        this.connectionTester = connectionTester;
        this.collectTaskMapper = collectTaskMapper;
        this.syncJobMapper = syncJobMapper;
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.complianceCleanupMapper = complianceCleanupMapper;
        this.schedulerClient = schedulerClient;
        this.dataSourceRefreshService = dataSourceRefreshService;
        this.sysUserService = sysUserService;
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
        entity.setStatus(DataSourceStatus.NORMAL.getCode());
        entity.setAutoCollectOnSave(Boolean.TRUE.equals(request.getAutoCollectOnSave()) ? 1 : 0);
        entity.setCreatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());

        dataSourceMapper.insert(entity);

        DataSourceDTO dto = toDTO(entity);
        if (Boolean.TRUE.equals(request.getAutoCollectOnSave())) {
            DataSourceConnection savedEntity = entity;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    autoCreateAndRunCollectTask(savedEntity);
                }
            });
            dto.setMessage("数据源保存成功，自动采集任务将在事务提交后触发");
        }
        return dto;
    }

    private String autoCreateAndRunCollectTask(DataSourceConnection entity) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String prefix = "自动采集-";
            String suffix = "-" + now.format(TIMESTAMP_FORMATTER);
            String dsName = entity.getName();
            int maxDsNameLen = 100 - prefix.length() - suffix.length();
            if (maxDsNameLen < 0) {
                maxDsNameLen = 0;
            }
            if (dsName.length() > maxDsNameLen) {
                dsName = dsName.substring(0, maxDsNameLen);
            }
            String taskName = prefix + dsName + suffix;
            List<String> scope = resolveCollectScope(entity);

            CollectTask task = new CollectTask();
            task.setName(taskName);
            task.setDatasourceId(entity.getId());
            task.setDatasourceName(entity.getName());
            task.setScope(scope);
            task.setCollectMode(CollectMode.FULL.getCode());
            task.setTriggerType(TaskTriggerType.MANUAL.getCode());
            task.setStatus(CollectTaskStatus.NEVER_EXECUTED.getCode());
            task.setDescription("数据源保存时自动创建的元数据采集任务");
            task.setScheduleEnabled(0);
            task.setCreatedBy(currentUserIdOrZero());
            task.setCreatedAt(now);
            collectTaskMapper.insert(task);

            Integer xxlJobId = schedulerClient.registerJob(workerExecutorAppName, COLLECT_TASK_HANDLER,
                    task.getId(), taskName, "", TaskTriggerType.MANUAL.getCode(), false, 0, 0);
            task.setXxlJobId(xxlJobId);
            collectTaskMapper.updateById(task);

            schedulerClient.triggerJob(xxlJobId, task.getId() + "," + TaskTriggerType.MANUAL.getCode());
            logger.info("数据源保存后自动采集任务已触发: datasourceId={}, taskId={}, xxlJobId={}",
                    entity.getId(), task.getId(), xxlJobId);
            return null;
        } catch (Exception e) {
            logger.error("数据源保存后自动采集异常: datasourceId={}", entity.getId(), e);
            return "自动采集任务触发失败: " + e.getMessage();
        }
    }

    private List<String> resolveCollectScope(DataSourceConnection entity) {
        DataSourceType type = DataSourceType.fromCode(entity.getType());
        if (type != null && type.hasSchemaLayer()) {
            String schema = StringUtils.hasText(entity.getSchemaName()) ? entity.getSchemaName()
                    : (type == DataSourceType.POSTGRESQL ? "public" : entity.getUsername());
            return StringUtils.hasText(schema) ? Collections.singletonList(schema) : Collections.emptyList();
        }
        String scope = entity.getDatabaseName();
        return StringUtils.hasText(scope) ? Collections.singletonList(scope) : Collections.emptyList();
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
        if (request.getAutoCollectOnSave() != null) {
            entity.setAutoCollectOnSave(Boolean.TRUE.equals(request.getAutoCollectOnSave()) ? 1 : 0);
        }
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
            throw new BusinessException(ErrorCode.HAS_REFERENCES, "数据源已被引用，无法删除", references);
        }

        // 级联删除已采集的元数据：先删子表字段，再删父表
        List<Long> tableIds = metadataTableMapper.selectIdsByDatasourceId(id);
        if (!tableIds.isEmpty()) {
            metadataColumnMapper.deleteByTableIds(tableIds);
        }
        metadataTableMapper.deleteByDatasourceId(id);

        // 级联删除合规检查结果（datasource_id 关联的历史检查记录，治理模块表）
        int removed = complianceCleanupMapper.deleteByDatasourceId(id);
        if (removed > 0) {
            logger.info("级联删除合规检查结果: datasourceId={}, count={}", id, removed);
        }

        dataSourceMapper.deleteById(id);
    }

    public DataSourceDTO getById(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        DataSourceDTO dto = toDTO(entity);
        fillUsernameNames(List.of(dto));
        return dto;
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
        fillUsernameNames(records);
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

    public List<String> getDatabases(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        String password = encryptionConfig.decrypt(entity.getEncryptedPassword());
        return connectionTester.extractDatabases(entity, password);
    }

    public List<String> getTables(Long id, String database, String schema) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        String password = encryptionConfig.decrypt(entity.getEncryptedPassword());
        return connectionTester.extractTables(entity, password, database, schema);
    }

    public List<DataSourceReferenceDTO> getReferences(Long id) {
        List<DataSourceReferenceDTO> references = new ArrayList<>();

        List<CollectTask> collectTasks = collectTaskMapper.selectActiveByDatasourceId(id);
        for (CollectTask task : collectTasks) {
            DataSourceReferenceDTO dto = new DataSourceReferenceDTO();
            dto.setTaskId(task.getId());
            dto.setTaskName(task.getName());
            dto.setStatus(task.getStatus());
            dto.setType(ReferenceType.COLLECT.getCode());
            references.add(dto);
        }

        List<SyncJob> syncJobs = syncJobMapper.selectBySourceDatasourceId(id);
        for (SyncJob job : syncJobs) {
            DataSourceReferenceDTO dto = new DataSourceReferenceDTO();
            dto.setTaskId(job.getId());
            dto.setTaskName(job.getName());
            dto.setStatus(job.getStatus());
            dto.setType(ReferenceType.SYNC.getCode());
            dto.setSourceDatabase(job.getSourceDatabase());
            dto.setSourceSchema(job.getSourceSchema());
            dto.setTargetDatabase(job.getTargetDatabase());
            dto.setTargetTable(job.getTargetTable());
            dto.setSyncMode(job.getSyncMode());
            dto.setTriggerType(job.getTriggerType());
            references.add(dto);
        }

        return references;
    }

    public void refreshAllStatuses() {
        dataSourceRefreshService.refreshAllStatuses();
    }

    private void updateStatus(DataSourceConnection entity, TestConnectionResult result) {
        entity.setStatus(result.isSuccess() ? DataSourceStatus.NORMAL.getCode() : DataSourceStatus.ERROR.getCode());
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
        dto.setAutoCollectOnSave(entity.getAutoCollectOnSave());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        return dto;
    }

    private void fillUsernameNames(List<DataSourceDTO> dtos) {
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
        for (DataSourceDTO dto : dtos) {
            if (dto.getCreatedBy() != null && dto.getCreatedBy() > 0) {
                dto.setCreatedByName(usernameMap.getOrDefault(dto.getCreatedBy(), "-"));
            }
            if (dto.getUpdatedBy() != null && dto.getUpdatedBy() > 0) {
                dto.setUpdatedByName(usernameMap.getOrDefault(dto.getUpdatedBy(), "-"));
            }
        }
    }

    private long countByName(String name) {
        return dataSourceMapper.selectCount(new QueryWrapper<DataSourceConnection>().eq("name", name));
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long currentUserIdOrZero() {
        Long userId = currentUserId();
        return userId == null ? 0L : userId;
    }
}
