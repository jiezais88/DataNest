package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.*;
import com.datanest.common.dto.DataSourceReferenceDTO;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.engineering.dto.DataSourceCreateRequest;
import com.datanest.engineering.dto.DataSourceDTO;
import com.datanest.engineering.dto.DataSourceQueryRequest;
import com.datanest.engineering.dto.DataSourceStatsDTO;
import com.datanest.engineering.dto.DataSourceUpdateRequest;
import com.datanest.engineering.entity.DataSourceConnection;
import com.datanest.engineering.mapper.*;
import com.datanest.governance.api.GovernanceDatasourceApi;
import com.datanest.governance.api.dto.AutoCreateCollectTaskRequest;
import com.datanest.governance.api.dto.DatasourceReferencesDTO;
import com.datanest.governance.api.dto.ReferenceItemDTO;
import com.datanest.realtime.api.CdcPipelineApi;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.task.core.dto.TestConnectionRequest;
import com.datanest.task.core.dto.TestConnectionResult;
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
import java.util.*;

@Service
public class DataSourceService {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceService.class);
    private static final String MASKED_PASSWORD = "********";

    private final DataSourceConnectionMapper dataSourceMapper;
    private final EncryptionConfig encryptionConfig;
    private final ConnectionTester connectionTester;
    private final SystemUserApi systemUserApi;
    private final EngineeringSyncJobApi engineeringSyncJobApi;
    private final GovernanceDatasourceApi governanceDatasourceApi;
    private final CdcPipelineApi cdcPipelineApi;

    public DataSourceService(DataSourceConnectionMapper dataSourceMapper, EncryptionConfig encryptionConfig,
                             ConnectionTester connectionTester,
                             SystemUserApi systemUserApi,
                             EngineeringSyncJobApi engineeringSyncJobApi,
                             GovernanceDatasourceApi governanceDatasourceApi,
                             CdcPipelineApi cdcPipelineApi) {
        this.dataSourceMapper = dataSourceMapper;
        this.encryptionConfig = encryptionConfig;
        this.connectionTester = connectionTester;
        this.systemUserApi = systemUserApi;
        this.engineeringSyncJobApi = engineeringSyncJobApi;
        this.governanceDatasourceApi = governanceDatasourceApi;
        this.cdcPipelineApi = cdcPipelineApi;
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

    /**
     * 自动建采集任务逻辑已下沉 governance（采集任务归治理域），本方法只组装入参并发 Feign。
     * 保持原语义：任何失败只记 error 并返回提示信息，不阻断数据源保存主流程。
     */
    private String autoCreateAndRunCollectTask(DataSourceConnection entity) {
        try {
            AutoCreateCollectTaskRequest request = new AutoCreateCollectTaskRequest();
            request.setDatasourceId(entity.getId());
            request.setDatasourceName(entity.getName());
            request.setType(entity.getType());
            request.setDatabaseName(entity.getDatabaseName());
            request.setSchemaName(entity.getSchemaName());
            request.setUsername(entity.getUsername());
            request.setCreatedBy(currentUserIdOrZero());
            Result<Long> result = governanceDatasourceApi.autoCreateCollectTask(request);
            Long taskId = result == null ? null : result.data();
            if (taskId == null) {
                logger.error("数据源保存后自动采集失败（governance 降级返回空）: datasourceId={}", entity.getId());
                return "自动采集任务触发失败: governance 服务不可用";
            }
            logger.info("数据源保存后自动采集任务已触发: datasourceId={}, taskId={}", entity.getId(), taskId);
            return null;
        } catch (Exception e) {
            logger.error("数据源保存后自动采集异常: datasourceId={}", entity.getId(), e);
            return "自动采集任务触发失败: " + e.getMessage();
        }
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

    /**
     * 删除数据源。事务边界说明（微服务化 3.4）：
     * 治理域级联删除（metadata_table/column、compliance_check_result、quality_score）已下沉 governance，
     * 经 Feign 远程执行且 fail-closed——远程失败抛异常中止整个删除，本地数据源保留、可重试，
     * 避免出现"数据源删了元数据残留"。远程先行的理由：远程失败无本地残留；若先删本地，
     * 远程失败会留下"数据源已删但元数据残留"且无法重试。
     * {@code @Transactional} 现在只覆盖本地 datasource_connection 删除。
     */
    @Transactional
    public void delete(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }

        // CDC 管道引用校验（Sprint 8 F2，fail-closed：直接调 Feign 不走 RemoteCalls 降级，
        // fallbackFactory 熔断时抛异常中止删除，避免误删仍被 CDC 管道引用的数据源）
        Result<List<CdcPipelineReferenceDTO>> cdcResult = cdcPipelineApi.listByDatasource(id);
        List<CdcPipelineReferenceDTO> cdcPipelines = cdcResult == null || cdcResult.data() == null
                ? List.of() : cdcResult.data();
        if (!cdcPipelines.isEmpty()) {
            String names = cdcPipelines.stream().map(CdcPipelineReferenceDTO::getName).toList().toString();
            throw new BusinessException(ErrorCode.CDC_DATASOURCE_REFERENCED,
                    "数据源已被 CDC 管道引用：" + names + "，请先删除管道");
        }

        List<DataSourceReferenceDTO> references = getReferences(id);
        if (!references.isEmpty()) {
            throw new BusinessException(ErrorCode.HAS_REFERENCES, "数据源已被引用，无法删除", references);
        }

        // 先远程级联删除治理域数据（fail-closed，不用 RemoteCalls 降级）：
        // 熔断 fallback 抛 IllegalStateException，其余异常统一转为业务异常，均中止删除
        try {
            governanceDatasourceApi.cascadeDelete(id);
        } catch (Exception e) {
            logger.error("治理元数据级联删除失败，已中止数据源删除: datasourceId={}", id, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "治理元数据级联删除失败，数据源删除已中止，请稍后重试: " + e.getMessage());
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

    /**
     * 连接状态统计（列表页顶部统计卡），避免前端拉全量列表计数。
     */
    public DataSourceStatsDTO listStats() {
        DataSourceStatsDTO stats = dataSourceMapper.selectStats();
        return stats == null ? new DataSourceStatsDTO() : stats;
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
        return connectionTester.extractSchemas(toDataSourceInfo(entity), password);
    }

    public List<String> getDatabases(Long id) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        String password = encryptionConfig.decrypt(entity.getEncryptedPassword());
        return connectionTester.extractDatabases(toDataSourceInfo(entity), password);
    }

    public List<String> getTables(Long id, String database, String schema) {
        DataSourceConnection entity = dataSourceMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        String password = encryptionConfig.decrypt(entity.getEncryptedPassword());
        return connectionTester.extractTables(toDataSourceInfo(entity), password, database, schema);
    }

    /** 本地实体 → 连接参数 DTO（ConnectionTester 签名收 DataSourceInfo，只取连接所需字段） */
    private static DataSourceInfo toDataSourceInfo(DataSourceConnection entity) {
        DataSourceInfo info = new DataSourceInfo();
        info.setType(entity.getType());
        info.setHost(entity.getHost());
        info.setPort(entity.getPort());
        info.setDatabaseName(entity.getDatabaseName());
        info.setSchemaName(entity.getSchemaName());
        info.setUsername(entity.getUsername());
        return info;
    }

    /**
     * 删除前引用检查（fail-fast，不用 RemoteCalls 降级：引用检查 silently 通过会导致误删）。
     * 采集任务/质量规则走 governance（治理域），同步任务走 engineering 自身契约（lb:// 自调用）。
     * 注：governance 返回的 metadataTables 不计入阻断引用——已采集元数据随删除级联清理（cascade-delete）。
     */
    public List<DataSourceReferenceDTO> getReferences(Long id) {
        List<DataSourceReferenceDTO> references = new ArrayList<>();

        Result<DatasourceReferencesDTO> govResult = governanceDatasourceApi.getReferences(id);
        DatasourceReferencesDTO govRefs = govResult == null ? null : govResult.data();
        if (govRefs != null) {
            for (ReferenceItemDTO task : govRefs.getCollectTasks() == null
                    ? List.<ReferenceItemDTO>of() : govRefs.getCollectTasks()) {
                DataSourceReferenceDTO dto = new DataSourceReferenceDTO();
                dto.setTaskId(task.getId());
                dto.setTaskName(task.getName());
                dto.setType(ReferenceType.COLLECT.getCode());
                references.add(dto);
            }
            for (ReferenceItemDTO rule : govRefs.getQualityRules() == null
                    ? List.<ReferenceItemDTO>of() : govRefs.getQualityRules()) {
                DataSourceReferenceDTO dto = new DataSourceReferenceDTO();
                dto.setTaskId(rule.getId());
                dto.setTaskName(rule.getName());
                dto.setType(ReferenceType.QUALITY_RULE.getCode());
                references.add(dto);
            }
        }

        Result<List<SyncJobInfo>> syncResult = engineeringSyncJobApi.listByDatasource(id);
        List<SyncJobInfo> syncJobs = syncResult == null || syncResult.data() == null
                ? List.of() : syncResult.data();
        for (SyncJobInfo job : syncJobs) {
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

    /**
     * 刷新全部活跃数据源（status IN NORMAL/ERROR）的连接状态：逐个连接测试 + 状态回写，全本地。
     * 微服务化 4.3：原 task-core-governance 的 DataSourceRefreshService 逻辑下沉本服务
     * （datasource_connection 归 engineering，无需再经 Feign 自调用），
     * 供 data-nest-job 经内部端点 {@code POST /engineering/internal/datasources/refresh-statuses} 触发。
     * 单个数据源失败只记 error 并回写 ERROR 状态，不影响其余数据源。
     */
    public void refreshAllStatuses() {
        List<DataSourceConnection> list = dataSourceMapper.selectList(new QueryWrapper<DataSourceConnection>()
                .in("status", DataSourceStatus.NORMAL.getCode(), DataSourceStatus.ERROR.getCode()));
        for (DataSourceConnection entity : list) {
            try {
                TestConnectionResult result = testAndUpdateStatus(entity.getId());
                logger.info("Refreshed data source status: id={}, name={}, success={}",
                        entity.getId(), entity.getName(), result.isSuccess());
            } catch (Exception e) {
                logger.error("Failed to refresh data source status: id={}, name={}", entity.getId(), entity.getName(), e);
                markRefreshError(entity.getId(), "定时刷新异常: " + e.getMessage());
            }
        }
    }

    /** 刷新异常时回写 ERROR 状态（失败仅记 warn，下轮刷新会再试） */
    private void markRefreshError(Long id, String errorMessage) {
        try {
            dataSourceMapper.update(null, new UpdateWrapper<DataSourceConnection>()
                    .set("status", DataSourceStatus.ERROR.getCode())
                    .set("error_message", errorMessage)
                    .set("last_test_time", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now())
                    .eq("id", id));
        } catch (Exception e) {
            logger.warn("回写数据源刷新异常状态失败: id={}", id, e);
        }
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
        Map<Long, String> usernameMap = usernames(userIds);
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
