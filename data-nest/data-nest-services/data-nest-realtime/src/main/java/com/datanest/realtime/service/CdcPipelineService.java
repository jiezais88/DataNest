package com.datanest.realtime.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.realtime.dto.CdcPipelineDTO;
import com.datanest.realtime.dto.CdcPipelineLogDTO;
import com.datanest.realtime.dto.CdcPipelineSaveRequest;
import com.datanest.realtime.dto.CdcPipelineStatsDTO;
import com.datanest.realtime.dto.CdcSourceValidateResult;
import com.datanest.realtime.dto.CdcTableMappingDTO;
import com.datanest.realtime.entity.CdcPipeline;
import com.datanest.realtime.entity.CdcPipelineLog;
import com.datanest.realtime.entity.CdcPipelineTable;
import com.datanest.realtime.mapper.CdcPipelineLogMapper;
import com.datanest.realtime.mapper.CdcPipelineMapper;
import com.datanest.realtime.mapper.CdcPipelineTableMapper;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CDC 管道核心服务：CRUD + 启停（Flink 作业提交 / cancel-with-savepoint）+ 日志 + Doris catalog 刷新。
 * <p>
 * 停止 = cancel-with-savepoint（savepoint 落 s3a://datalake/savepoints）；
 * 启动时 savepoint_path 有值优先从 savepoint 恢复（不丢不重），编辑管道后清空 savepoint_path
 * （配置变更后旧 savepoint 不可恢复），无 savepoint 时按 sync_mode/startup_mode 从头跑。
 */
@Service
public class CdcPipelineService {

    private static final Logger logger = LoggerFactory.getLogger(CdcPipelineService.class);

    /** 正在执行 stop（cancel-with-savepoint）的管道：监控轮询据此跳过「外部停止」误报 */
    private final Set<Long> stoppingPipelineIds = ConcurrentHashMap.newKeySet();

    private final CdcPipelineMapper pipelineMapper;
    private final CdcPipelineTableMapper tableMapper;
    private final CdcPipelineLogMapper logMapper;
    private final CdcYamlBuilder yamlBuilder;
    private final FlinkJobService flinkJobService;
    private final SourcePrecheckService precheckService;
    private final DorisCatalogService dorisCatalogService;
    private final EngineeringDatasourceApi engineeringDatasourceApi;
    private final SystemUserApi systemUserApi;

    public CdcPipelineService(CdcPipelineMapper pipelineMapper,
                              CdcPipelineTableMapper tableMapper,
                              CdcPipelineLogMapper logMapper,
                              CdcYamlBuilder yamlBuilder,
                              FlinkJobService flinkJobService,
                              SourcePrecheckService precheckService,
                              DorisCatalogService dorisCatalogService,
                              EngineeringDatasourceApi engineeringDatasourceApi,
                              SystemUserApi systemUserApi) {
        this.pipelineMapper = pipelineMapper;
        this.tableMapper = tableMapper;
        this.logMapper = logMapper;
        this.yamlBuilder = yamlBuilder;
        this.flinkJobService = flinkJobService;
        this.precheckService = precheckService;
        this.dorisCatalogService = dorisCatalogService;
        this.engineeringDatasourceApi = engineeringDatasourceApi;
        this.systemUserApi = systemUserApi;
    }

    /** 创建管道（初始 STOPPED） */
    @Transactional
    public CdcPipelineDTO create(CdcPipelineSaveRequest request) {
        validateSaveRequest(request, null);

        CdcPipeline entity = new CdcPipeline();
        applySaveRequest(entity, request);
        entity.setStatus(CdcPipeline.STATUS_STOPPED);
        entity.setTotalChanges(0L);
        entity.setCreatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        pipelineMapper.insert(entity);

        insertTables(entity.getId(), request.getTables());
        writeLog(entity.getId(), CdcPipelineLog.LEVEL_INFO, "管道创建");
        return detail(entity.getId());
    }

    /** 编辑管道（仅 STOPPED；全量替换表映射；清空 savepoint_path） */
    @Transactional
    public CdcPipelineDTO update(Long id, CdcPipelineSaveRequest request) {
        CdcPipeline entity = getPipeline(id);
        if (!CdcPipeline.STATUS_STOPPED.equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STATUS_INVALID, "仅停止状态的管道可编辑，请先停止");
        }
        validateSaveRequest(request, id);

        applySaveRequest(entity, request);
        // 配置变更后旧 savepoint 与新配置不匹配、不可恢复，清空避免误用。
        // 注意用 UpdateWrapper 显式 set：updateById 忽略 null 字段，savepoint_path 清不掉
        UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                .eq("id", id)
                .set("name", entity.getName())
                .set("description", entity.getDescription())
                .set("source_datasource_id", entity.getSourceDatasourceId())
                .set("source_database", entity.getSourceDatabase())
                .set("target_database", entity.getTargetDatabase())
                .set("sync_mode", entity.getSyncMode())
                .set("startup_mode", entity.getStartupMode())
                .set("write_mode", entity.getWriteMode())
                .set("config_json", entity.getConfigJson())
                .set("savepoint_path", null)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        pipelineMapper.update(null, update);

        // 全量替换表映射（先删后插）
        tableMapper.delete(new QueryWrapper<CdcPipelineTable>().eq("pipeline_id", id));
        insertTables(id, request.getTables());
        writeLog(id, CdcPipelineLog.LEVEL_INFO, "管道配置已更新（savepoint 已清空，下次启动按启动位点从头跑）");
        return detail(id);
    }

    /**
     * 删除管道（RUNNING 中禁止；级联删表映射/日志）。
     * savepoint 文件的物理清理不做（需引入 S3 客户端重依赖），TODO：后续接入 MinIO 客户端清理。
     */
    @Transactional
    public void delete(Long id) {
        CdcPipeline entity = getPipeline(id);
        if (CdcPipeline.STATUS_RUNNING.equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STATUS_INVALID, "管道运行中，请先停止");
        }
        tableMapper.delete(new QueryWrapper<CdcPipelineTable>().eq("pipeline_id", id));
        logMapper.delete(new QueryWrapper<CdcPipelineLog>().eq("pipeline_id", id));
        pipelineMapper.deleteById(id);
    }

    /** 管道统计（列表页顶部统计卡）：按状态分组计数 + 已同步表总数。 */
    public CdcPipelineStatsDTO stats() {
        Map<String, Long> byStatus = new HashMap<>();
        for (Map<String, Object> row : pipelineMapper.selectMaps(new QueryWrapper<CdcPipeline>()
                .select("status", "COUNT(*) AS cnt").groupBy("status"))) {
            byStatus.put((String) row.get("status"), ((Number) row.get("cnt")).longValue());
        }
        CdcPipelineStatsDTO dto = new CdcPipelineStatsDTO();
        dto.setRunning(byStatus.getOrDefault(CdcPipeline.STATUS_RUNNING, 0L));
        dto.setStopped(byStatus.getOrDefault(CdcPipeline.STATUS_STOPPED, 0L));
        dto.setError(byStatus.getOrDefault(CdcPipeline.STATUS_ERROR, 0L));
        Long syncedTables = tableMapper.selectCount(null);
        dto.setSyncedTables(syncedTables == null ? 0L : syncedTables);
        return dto;
    }

    /** 分页查询（status/keyword 过滤，id 倒序，批量回填数据源名） */
    public PageResult<CdcPipelineDTO> page(String status, String keyword, long page, long pageSize) {
        QueryWrapper<CdcPipeline> wrapper = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like("name", keyword.trim());
        }
        wrapper.orderByDesc("id");
        IPage<CdcPipeline> result = pipelineMapper.selectPage(new Page<>(page, pageSize), wrapper);

        List<CdcPipelineDTO> records = result.getRecords().stream().map(this::toDTO).toList();
        fillDatasourceNames(records);
        fillPageTables(records);
        fillUserNames(records);
        return PageResult.of(records, result.getTotal(), page, pageSize);
    }

    /** 批量回填列表行的表映射（「源」列展示「orders 等 N 表」），一次 IN 查询避免逐行 N+1。 */
    private void fillPageTables(List<CdcPipelineDTO> records) {
        List<Long> ids = records.stream().map(CdcPipelineDTO::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, List<CdcPipelineTable>> byPipeline = tableMapper.selectList(
                        new QueryWrapper<CdcPipelineTable>().in("pipeline_id", ids))
                .stream().collect(Collectors.groupingBy(CdcPipelineTable::getPipelineId));
        for (CdcPipelineDTO dto : records) {
            dto.setTables((byPipeline.getOrDefault(dto.getId(), List.of()).stream()
                    .map(t -> {
                        CdcTableMappingDTO m = new CdcTableMappingDTO();
                        m.setSourceTable(t.getSourceTable());
                        m.setTargetTable(t.getTargetTable());
                        m.setPrimaryKey(t.getPrimaryKey());
                        return m;
                    })
                    .toList()));
        }
    }

    /** 详情（含表映射 + 数据源名/用户名回填，降级 null） */
    public CdcPipelineDTO detail(Long id) {
        CdcPipeline entity = getPipeline(id);
        CdcPipelineDTO dto = toDTO(entity);
        dto.setTables(listTables(id));
        String datasourceName = RemoteCalls.execute("engineering.datasourceGetById", () -> {
            Result<DataSourceInfo> result = engineeringDatasourceApi.getById(entity.getSourceDatasourceId());
            DataSourceInfo info = result == null ? null : result.data();
            return info == null ? null : info.getName();
        }, null);
        dto.setSourceDatasourceName(datasourceName);
        fillUserNames(List.of(dto));
        return dto;
    }

    /**
     * 启动管道：STOPPED/ERROR 可启动；无 savepoint 时先预检源数据源；
     * 组装 YAML → 提交 Flink Session 集群；失败置 ERROR + 抛 8007。
     */
    public CdcPipelineDTO start(Long id) {
        CdcPipeline entity = getPipeline(id);
        if (CdcPipeline.STATUS_RUNNING.equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STATUS_INVALID, "管道已在运行中");
        }
        if (!CdcPipeline.STATUS_STOPPED.equals(entity.getStatus())
                && !CdcPipeline.STATUS_ERROR.equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STATUS_INVALID);
        }

        String savepointPath = entity.getSavepointPath();
        boolean restoreFromSavepoint = savepointPath != null && !savepointPath.isBlank();

        // 无 savepoint 可恢复时先做源数据源预检（连通性/增量日志/源库存在性），失败抛对应错误码
        if (!restoreFromSavepoint) {
            CdcSourceValidateResult precheck = precheckService.validate(
                    entity.getSourceDatasourceId(), entity.getSourceDatabase());
            if (!Boolean.TRUE.equals(precheck.getSuccess())) {
                CdcSourceValidateResult.CheckItem failed = precheck.getChecks().stream()
                        .filter(c -> !Boolean.TRUE.equals(c.getPassed()))
                        .findFirst().orElse(null);
                String reason = failed == null ? "未知原因" : failed.getName() + ": " + failed.getMessage();
                boolean connectionIssue = failed != null && failed.getName().contains("连通性");
                throw new BusinessException(
                        connectionIssue ? ErrorCode.CDC_SOURCE_CONNECTION_FAILED : ErrorCode.CDC_SOURCE_BINLOG_DISABLED,
                        reason);
            }
        }

        // 组装 YAML（连接信息 fail-closed 反查 + 解密）
        DataSourceInfo datasource = precheckService.getDatasource(entity.getSourceDatasourceId());
        List<CdcPipelineTable> tables = tableMapper.selectList(
                new QueryWrapper<CdcPipelineTable>().eq("pipeline_id", id));
        String yaml = yamlBuilder.build(entity, tables, datasource.getType(), datasource.getHost(),
                datasource.getPort(), datasource.getUsername(), precheckService.decryptPassword(datasource));

        // CAS 占位防并发重复提交：双击/并发请求都能通过上面的读检查，
        // 不设防会重复提交 Flink 作业（同 server-id 区间互相干扰 binlog），先提交的作业成孤儿泄漏在集群
        int claimed = pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                .eq("id", id)
                .in("status", CdcPipeline.STATUS_STOPPED, CdcPipeline.STATUS_ERROR)
                .set("status", CdcPipeline.STATUS_RUNNING)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STATUS_INVALID,
                    "管道状态已变化（可能正在启动），请刷新后重试");
        }

        try {
            // checkpoint 间隔高级配置（秒→毫秒）经提交侧 flinkConfig 下发：
            // FlinkPipelineComposer.compose 只消费 YAML pipeline 段固定键（name/parallelism 等），
            // execution.checkpointing.interval 写进 YAML 不生效（3.6.0-2.2 源码 + 实测确认）
            CdcYamlBuilder.AdvancedConfig advanced = CdcYamlBuilder.parseAdvancedConfig(entity.getConfigJson());
            Long checkpointIntervalMsOverride = advanced.checkpointIntervalSeconds() == null
                    ? null : advanced.checkpointIntervalSeconds() * 1000L;
            String flinkJobId = flinkJobService.submit(yaml,
                    restoreFromSavepoint ? savepointPath : null, checkpointIntervalMsOverride);
            // UpdateWrapper 显式 set：updateById 忽略 null 字段，last_error 清不掉
            // started_at = 最近一次启动成功时间（stop 不清空；仅在提交成功后写入，失败置 ERROR 不动它）
            UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                    .eq("id", id)
                    .set("flink_job_id", flinkJobId)
                    .set("status", CdcPipeline.STATUS_RUNNING)
                    .set("last_error", null)
                    .set("started_at", LocalDateTime.now())
                    .set("updated_by", currentUserId())
                    .set("updated_at", LocalDateTime.now());
            pipelineMapper.update(null, update);
            writeLog(id, CdcPipelineLog.LEVEL_INFO,
                    restoreFromSavepoint
                            ? "管道启动成功（从 savepoint 恢复: " + savepointPath + "），Flink 作业 ID: " + flinkJobId
                            : "管道启动成功，Flink 作业 ID: " + flinkJobId);
            return detail(id);
        } catch (Exception e) {
            logger.error("CDC 管道启动失败: pipelineId={}", id, e);
            String lastError = truncate("Flink 作业提交失败: " + e.getMessage(), 2000);
            // 回退 CAS 占位：置 ERROR + last_error（UpdateWrapper 显式 set，避免 updateById 忽略 null/带旧值）
            // TODO：execute() 在作业已提交成功但响应阶段失败的极端场景下会在集群残留孤儿作业，后续可按 name 尝试 cancel 补偿
            UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                    .eq("id", id)
                    .set("status", CdcPipeline.STATUS_ERROR)
                    .set("last_error", lastError)
                    .set("updated_by", currentUserId())
                    .set("updated_at", LocalDateTime.now());
            pipelineMapper.update(null, update);
            writeLog(id, CdcPipelineLog.LEVEL_ERROR, "管道启动失败: " + lastError);
            throw new BusinessException(ErrorCode.CDC_PIPELINE_START_FAILED, e.getMessage());
        }
    }

    /**
     * 停止管道 = cancel-with-savepoint：存 savepoint_path、置 STOPPED、清 flink_job_id 与延迟。
     * 失败抛 8008，管道状态不动（用户可重试，或到 Flink 集群侧处理残留作业）。
     */
    public CdcPipelineDTO stop(Long id) {
        CdcPipeline entity = getPipeline(id);
        if (!CdcPipeline.STATUS_RUNNING.equals(entity.getStatus()) || entity.getFlinkJobId() == null) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STATUS_INVALID, "管道未在运行，无法停止");
        }

        String savepointPath;
        // 标记停止中：stop-with-savepoint 轮询窗口（最长 60s）内作业已表现为 CANCELED，
        // 监控轮询据此跳过「外部停止」处理（见 CdcMonitorService.pollOne）
        stoppingPipelineIds.add(id);
        try {
            savepointPath = flinkJobService.stopWithSavepoint(entity.getFlinkJobId());
        } catch (Exception e) {
            logger.error("CDC 管道停止失败: pipelineId={}, flinkJobId={}", id, entity.getFlinkJobId(), e);
            writeLog(id, CdcPipelineLog.LEVEL_ERROR, "管道停止失败: " + e.getMessage());
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STOP_FAILED, e.getMessage());
        } finally {
            stoppingPipelineIds.remove(id);
        }

        // UpdateWrapper 显式 set：updateById 忽略 null 字段，flink_job_id/current_lag_seconds 清不掉
        UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                .eq("id", id)
                .set("savepoint_path", savepointPath)
                .set("status", CdcPipeline.STATUS_STOPPED)
                .set("flink_job_id", null)
                .set("current_lag_seconds", null)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        pipelineMapper.update(null, update);
        writeLog(id, CdcPipelineLog.LEVEL_INFO, "管道已停止（savepoint: " + savepointPath + "）");
        return detail(id);
    }

    /** 管道运行日志分页（id 倒序） */
    public PageResult<CdcPipelineLogDTO> logs(Long id, long page, long pageSize) {
        getPipeline(id);
        IPage<CdcPipelineLog> result = logMapper.selectPage(new Page<>(page, pageSize),
                new QueryWrapper<CdcPipelineLog>().eq("pipeline_id", id).orderByDesc("id"));
        List<CdcPipelineLogDTO> records = result.getRecords().stream().map(log -> {
            CdcPipelineLogDTO dto = new CdcPipelineLogDTO();
            dto.setId(log.getId());
            dto.setLevel(log.getLevel());
            dto.setMessage(log.getMessage());
            dto.setCreatedAt(log.getCreatedAt());
            return dto;
        }).toList();
        return PageResult.of(records, result.getTotal(), page, pageSize);
    }

    /** 刷新 Doris 外部 catalog（让 Doris 感知湖仓新表/新数据） */
    public void refreshCatalog(Long id) {
        getPipeline(id);
        try {
            dorisCatalogService.refreshCatalog();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_TARGET_WRITE_FAILED, e.getMessage());
        }
        writeLog(id, CdcPipelineLog.LEVEL_INFO, "Doris catalog 已刷新");
    }

    /** 按源数据源查询引用它的管道（internal 契约，删除数据源前置校验用） */
    public List<CdcPipelineReferenceDTO> listByDatasource(Long datasourceId) {
        return pipelineMapper.selectList(new QueryWrapper<CdcPipeline>()
                        .eq("source_datasource_id", datasourceId))
                .stream()
                .map(p -> {
                    CdcPipelineReferenceDTO dto = new CdcPipelineReferenceDTO();
                    dto.setId(p.getId());
                    dto.setName(p.getName());
                    return dto;
                })
                .toList();
    }

    /** 写管道运行日志（供监控轮询等服务复用） */
    public void writeLog(Long pipelineId, String level, String message) {
        CdcPipelineLog log = new CdcPipelineLog();
        log.setPipelineId(pipelineId);
        log.setLevel(level);
        log.setMessage(message);
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
    }

    /** 管道是否正在执行 stop（cancel-with-savepoint 轮询窗口内），监控轮询据此跳过「外部停止」误报 */
    public boolean isStopping(Long pipelineId) {
        return stoppingPipelineIds.contains(pipelineId);
    }

    // ==================== 私有辅助 ====================

    private CdcPipeline getPipeline(Long id) {
        CdcPipeline entity = pipelineMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_NOT_FOUND);
        }
        return entity;
    }

    /** 保存请求公共校验（excludeId 非空时为编辑场景，重名校验排除自身） */
    private void validateSaveRequest(CdcPipelineSaveRequest request, Long excludeId) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID, "管道名称不能为空");
        }
        QueryWrapper<CdcPipeline> nameWrapper = new QueryWrapper<CdcPipeline>().eq("name", request.getName().trim());
        if (excludeId != null) {
            nameWrapper.ne("id", excludeId);
        }
        if (pipelineMapper.selectCount(nameWrapper) > 0) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_NAME_EXISTS);
        }
        if (request.getSourceDatasourceId() == null) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID, "源数据源不能为空");
        }
        if (request.getSourceDatabase() == null || request.getSourceDatabase().isBlank()) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID, "源库名不能为空");
        }
        if (request.getTargetDatabase() == null || request.getTargetDatabase().isBlank()) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID, "目标库名不能为空");
        }
        // 枚举校验
        if (!CdcPipeline.SYNC_MODE_FULL_AND_INCREMENT.equals(request.getSyncMode())
                && !CdcPipeline.SYNC_MODE_INCREMENTAL_ONLY.equals(request.getSyncMode())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "非法的同步模式: " + request.getSyncMode());
        }
        if (!CdcPipeline.STARTUP_MODE_INITIAL.equals(request.getStartupMode())
                && !CdcPipeline.STARTUP_MODE_LATEST_OFFSET.equals(request.getStartupMode())
                && !CdcPipeline.STARTUP_MODE_EARLIEST_OFFSET.equals(request.getStartupMode())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "非法的启动位点: " + request.getStartupMode());
        }
        // 全量+增量固定 initial 启动位点；仅增量才允许选 latest/earliest
        if (CdcPipeline.SYNC_MODE_FULL_AND_INCREMENT.equals(request.getSyncMode())
                && !CdcPipeline.STARTUP_MODE_INITIAL.equals(request.getStartupMode())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "全量+增量模式启动位点固定为 INITIAL");
        }
        // 反向拦截（2026-08-10 Sprint8 E2E 发现补齐）：仅增量 + INITIAL 是矛盾组合，
        // Flink 会按 initial 真跑全量快照，静默违背「仅增量」意图（前端有联动+防御，API 侧兜底）
        if (CdcPipeline.SYNC_MODE_INCREMENTAL_ONLY.equals(request.getSyncMode())
                && CdcPipeline.STARTUP_MODE_INITIAL.equals(request.getStartupMode())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "仅增量模式启动位点不允许 INITIAL，请选择 LATEST_OFFSET / EARLIEST_OFFSET");
        }
        // 按源类型校验（同时兜底数据源存在性与类型白名单）：PG connector 无 earliest-offset，保存期拦截
        DataSourceInfo datasource = precheckService.getDatasource(request.getSourceDatasourceId());
        if (SourcePrecheckService.TYPE_POSTGRESQL.equalsIgnoreCase(datasource.getType())
                && CdcPipeline.STARTUP_MODE_EARLIEST_OFFSET.equals(request.getStartupMode())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "PostgreSQL 源不支持「从最早位点」启动（connector 仅支持 initial/latest-offset），"
                            + "请改用「从最新位点」或「全量+增量」");
        }
        if (!CdcPipeline.WRITE_MODE_UPSERT.equals(request.getWriteMode())
                && !CdcPipeline.WRITE_MODE_APPEND.equals(request.getWriteMode())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "非法的写入模式: " + request.getWriteMode());
        }
        if (request.getTables() == null || request.getTables().isEmpty()) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID, "表映射不能为空");
        }
        // 高级配置（configJson 约定键）解析与范围校验：非法 JSON/非整数在 parseAdvancedConfig 内抛 8000
        CdcYamlBuilder.AdvancedConfig advanced = CdcYamlBuilder.parseAdvancedConfig(request.getConfigJson());
        if (advanced.parallelism() != null && (advanced.parallelism() < 1 || advanced.parallelism() > 8)) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "高级配置 parallelism 取值范围为 1~8: " + advanced.parallelism());
        }
        if (advanced.checkpointIntervalSeconds() != null && advanced.checkpointIntervalSeconds() < 3) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "高级配置 checkpointIntervalSeconds 最小为 3 秒: " + advanced.checkpointIntervalSeconds());
        }
        if (advanced.schemaChangeBehavior() != null
                && !CdcYamlBuilder.SCHEMA_CHANGE_BEHAVIORS.contains(advanced.schemaChangeBehavior())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "高级配置 schemaChangeBehavior 仅支持 EVOLVE/LENIENT/EXCEPTION: "
                            + advanced.schemaChangeBehavior());
        }
        if (advanced.scanChunkSize() != null
                && (advanced.scanChunkSize() < 16 || advanced.scanChunkSize() > 1048576)) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "高级配置 scanChunkSize 取值范围为 16~1048576: " + advanced.scanChunkSize());
        }
        for (CdcTableMappingDTO table : request.getTables()) {
            if (table.getSourceTable() == null || table.getSourceTable().isBlank()) {
                throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID, "源表名不能为空");
            }
            // targetTable 可空，落库前默认同名（见 insertTables）
            // UPSERT 模式主键必填（列实际存在性在启动预检/作业运行时校验）
            if (CdcPipeline.WRITE_MODE_UPSERT.equals(request.getWriteMode())
                    && (table.getPrimaryKey() == null || table.getPrimaryKey().isBlank())) {
                throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                        "UPSERT 模式表 " + table.getSourceTable() + " 必须配置主键");
            }
        }
    }

    private void applySaveRequest(CdcPipeline entity, CdcPipelineSaveRequest request) {
        entity.setName(request.getName().trim());
        entity.setDescription(request.getDescription());
        entity.setSourceDatasourceId(request.getSourceDatasourceId());
        entity.setSourceDatabase(request.getSourceDatabase().trim());
        entity.setTargetDatabase(request.getTargetDatabase().trim());
        entity.setSyncMode(request.getSyncMode());
        entity.setStartupMode(request.getStartupMode());
        entity.setWriteMode(request.getWriteMode());
        entity.setConfigJson(request.getConfigJson());
    }

    /** 落库表映射（targetTable 空则默认同源表名） */
    private void insertTables(Long pipelineId, List<CdcTableMappingDTO> tables) {
        for (CdcTableMappingDTO mapping : tables) {
            CdcPipelineTable entity = new CdcPipelineTable();
            entity.setPipelineId(pipelineId);
            entity.setSourceTable(mapping.getSourceTable().trim());
            entity.setTargetTable(mapping.getTargetTable() == null || mapping.getTargetTable().isBlank()
                    ? mapping.getSourceTable().trim() : mapping.getTargetTable().trim());
            entity.setPrimaryKey(mapping.getPrimaryKey());
            entity.setCreatedAt(LocalDateTime.now());
            tableMapper.insert(entity);
        }
    }

    private List<CdcTableMappingDTO> listTables(Long pipelineId) {
        return tableMapper.selectList(new QueryWrapper<CdcPipelineTable>()
                        .eq("pipeline_id", pipelineId).orderByAsc("id"))
                .stream()
                .map(t -> {
                    CdcTableMappingDTO dto = new CdcTableMappingDTO();
                    dto.setSourceTable(t.getSourceTable());
                    dto.setTargetTable(t.getTargetTable());
                    dto.setPrimaryKey(t.getPrimaryKey());
                    return dto;
                })
                .toList();
    }

    private CdcPipelineDTO toDTO(CdcPipeline entity) {
        CdcPipelineDTO dto = new CdcPipelineDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setSourceDatasourceId(entity.getSourceDatasourceId());
        dto.setSourceDatabase(entity.getSourceDatabase());
        dto.setTargetDatabase(entity.getTargetDatabase());
        dto.setSyncMode(entity.getSyncMode());
        dto.setStartupMode(entity.getStartupMode());
        dto.setWriteMode(entity.getWriteMode());
        dto.setStatus(entity.getStatus());
        dto.setFlinkJobId(entity.getFlinkJobId());
        dto.setSavepointPath(entity.getSavepointPath());
        dto.setCurrentLagSeconds(entity.getCurrentLagSeconds());
        dto.setTotalChanges(entity.getTotalChanges());
        dto.setLastError(entity.getLastError());
        dto.setConfigJson(entity.getConfigJson());
        dto.setStartedAt(entity.getStartedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    /** 批量回填数据源名（engineering batchGet，失败降级空 Map 不阻断列表） */
    private void fillDatasourceNames(List<CdcPipelineDTO> records) {
        List<Long> datasourceIds = records.stream()
                .map(CdcPipelineDTO::getSourceDatasourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (datasourceIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = RemoteCalls.execute("engineering.datasourceBatchGet", () -> {
            IdsRequest request = new IdsRequest();
            request.setIds(datasourceIds);
            Result<Map<Long, DataSourceInfo>> result = engineeringDatasourceApi.batchGet(request);
            Map<Long, DataSourceInfo> data = result == null ? null : result.data();
            if (data == null) {
                return Map.<Long, String>of();
            }
            return data.values().stream()
                    .collect(Collectors.toMap(DataSourceInfo::getId, DataSourceInfo::getName, (a, b) -> a));
        }, Map.of());
        records.forEach(dto -> dto.setSourceDatasourceName(nameMap.get(dto.getSourceDatasourceId())));
    }

    /**
     * 批量回填创建人/更新人用户名（system usernames 批量端点，一次查询避免逐行 N+1；
     * 失败降级空 Map，名称列为 null 不阻断）。
     */
    private void fillUserNames(List<CdcPipelineDTO> records) {
        List<Long> userIds = records.stream()
                .flatMap(dto -> Stream.of(dto.getCreatedBy(), dto.getUpdatedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> usernameMap = RemoteCalls.execute("system.usernames", () -> {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds);
            return result == null || result.data() == null ? Map.<Long, String>of() : result.data();
        }, Map.of());
        records.forEach(dto -> {
            dto.setCreatedByName(usernameMap.get(dto.getCreatedBy()));
            dto.setUpdatedByName(usernameMap.get(dto.getUpdatedBy()));
        });
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            // 非登录上下文（如内部调用/监控线程）不写创建人
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
