package com.datanest.realtime.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.alert.api.AlertApi;
import com.datanest.common.constant.AlertConstants;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.realtime.dto.CdcClusterInfoDTO;
import com.datanest.realtime.dto.CdcPipelineCheckpointsDTO;
import com.datanest.realtime.dto.CdcPipelineDTO;
import com.datanest.realtime.dto.CdcPipelineLogDTO;
import com.datanest.realtime.dto.CdcPipelineMetricsDTO;
import com.datanest.realtime.dto.CdcPipelineSaveRequest;
import com.datanest.realtime.dto.CdcPipelineStatsDTO;
import com.datanest.realtime.dto.CdcSourceValidateResult;
import com.datanest.realtime.dto.CdcTableMappingDTO;
import com.datanest.realtime.entity.CdcMetricMinute;
import com.datanest.realtime.entity.CdcPipeline;
import com.datanest.realtime.entity.CdcPipelineLog;
import com.datanest.realtime.entity.CdcPipelineTable;
import com.datanest.realtime.mapper.CdcMetricMinuteMapper;
import com.datanest.realtime.mapper.CdcPipelineLogMapper;
import com.datanest.realtime.mapper.CdcPipelineMapper;
import com.datanest.realtime.mapper.CdcPipelineTableMapper;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.realtime.api.dto.CdcPipelineSubscribeDTO;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final CdcMetricMinuteMapper metricMinuteMapper;
    private final CdcYamlBuilder yamlBuilder;
    private final FlinkJobService flinkJobService;
    private final SourcePrecheckService precheckService;
    private final DorisCatalogService dorisCatalogService;
    private final SavepointFileCleaner savepointFileCleaner;
    private final EngineeringDatasourceApi engineeringDatasourceApi;
    private final SystemUserApi systemUserApi;
    private final AlertApi alertApi;

    public CdcPipelineService(CdcPipelineMapper pipelineMapper,
                              CdcPipelineTableMapper tableMapper,
                              CdcPipelineLogMapper logMapper,
                              CdcMetricMinuteMapper metricMinuteMapper,
                              CdcYamlBuilder yamlBuilder,
                              FlinkJobService flinkJobService,
                              SourcePrecheckService precheckService,
                              DorisCatalogService dorisCatalogService,
                              SavepointFileCleaner savepointFileCleaner,
                              EngineeringDatasourceApi engineeringDatasourceApi,
                              SystemUserApi systemUserApi,
                              AlertApi alertApi) {
        this.pipelineMapper = pipelineMapper;
        this.tableMapper = tableMapper;
        this.logMapper = logMapper;
        this.metricMinuteMapper = metricMinuteMapper;
        this.yamlBuilder = yamlBuilder;
        this.flinkJobService = flinkJobService;
        this.precheckService = precheckService;
        this.dorisCatalogService = dorisCatalogService;
        this.savepointFileCleaner = savepointFileCleaner;
        this.engineeringDatasourceApi = engineeringDatasourceApi;
        this.systemUserApi = systemUserApi;
        this.alertApi = alertApi;
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
        // Sprint 9 F2（T3）：替换时同步物理清理失效的旧 savepoint 文件。
        // Review 2026-08-11 修复：清理挪到事务提交后（防回滚后 savepoint_path 指向已删文件）。
        String oldSavepoint = entity.getSavepointPath();
        if (oldSavepoint != null && !oldSavepoint.isBlank()) {
            afterCommitCleanSavepoint(id, oldSavepoint, "编辑管道清理失效 savepoint 文件失败（文件可能残留，请运维侧处理）");
        }
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
     * 删除管道（RUNNING 中禁止；级联删表映射/日志/指标历史）。
     * Sprint 9 F2（T3）：级联物理清理其 savepoint 文件（删文件失败不阻断删除主流程，warn 留痕）；
     * Sprint 9 F3：级联解绑告警规则对象（alert-api rules/by-object，沿用现有级联语义）。
     */
    @Transactional
    public void delete(Long id) {
        CdcPipeline entity = getPipeline(id);
        if (CdcPipeline.STATUS_RUNNING.equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_STATUS_INVALID, "管道运行中，请先停止");
        }
        // 级联清理 savepoint 文件（fail-open：删文件失败不阻断删除主流程，R5；
        // Review 2026-08-11 修复：挪到事务提交后，防回滚后路径指向已删文件）
        if (entity.getSavepointPath() != null && !entity.getSavepointPath().isBlank()) {
            afterCommitCleanSavepoint(id, entity.getSavepointPath(),
                    "删除管道时清理 savepoint 文件失败（文件可能残留，请运维侧处理）");
        }
        // 级联解绑告警规则对象（fail-open：alert 不可达不阻断删除）
        try {
            alertApi.deleteRuleByObject(AlertConstants.OBJECT_TYPE_CDC_PIPELINE, id);
        } catch (Exception e) {
            logger.warn("删除管道时解绑告警规则对象失败（不阻断删除）: pipelineId={}, error={}", id, e.getMessage());
        }
        // 级联清理 PG 复制槽（fail-open：PG wal_sender 连接数有限，槽残留耗尽后新作业全部起不来，
        // 2026-08-11 E2E 实测 max_wal_senders 被打满；槽名 datanest_cdc_<pipelineId>）
        dropPgReplicationSlotIfNeeded(entity);
        tableMapper.delete(new QueryWrapper<CdcPipelineTable>().eq("pipeline_id", id));
        logMapper.delete(new QueryWrapper<CdcPipelineLog>().eq("pipeline_id", id));
        metricMinuteMapper.delete(new QueryWrapper<CdcMetricMinute>().eq("pipeline_id", id));
        pipelineMapper.deleteById(id);
    }

    /** 删除管道时清理 PG 源复制槽（datanest_cdc_<pipelineId>；best effort fail-open，失败只记 warn） */
    private void dropPgReplicationSlotIfNeeded(CdcPipeline entity) {
        try {
            DataSourceInfo datasource = precheckService.getDatasource(entity.getSourceDatasourceId());
            if (!SourcePrecheckService.TYPE_POSTGRESQL.equalsIgnoreCase(datasource.getType())) {
                return;
            }
            String slotName = "datanest_cdc_" + entity.getId();
            String url = String.format("jdbc:postgresql://%s:%d/%s",
                    datasource.getHost(), datasource.getPort(), entity.getSourceDatabase());
            try (Connection conn = DriverManager.getConnection(url, datasource.getUsername(),
                    precheckService.decryptPassword(datasource));
                 Statement st = conn.createStatement()) {
                st.execute("SELECT pg_drop_replication_slot('" + slotName + "') WHERE EXISTS "
                        + "(SELECT 1 FROM pg_replication_slots WHERE slot_name = '" + slotName + "')");
                logger.info("删除管道时清理 PG 复制槽: pipelineId={}, slot={}", entity.getId(), slotName);
            }
        } catch (Exception e) {
            logger.warn("清理 PG 复制槽失败（不阻断删除，槽位可能残留需运维处理）: pipelineId={}, error={}",
                    entity.getId(), e.getMessage());
        }
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

    /** Flink 集群容量（Task Slot 总数/空闲数，向导并行度提示；集群不可达返回空字段，前端降级） */
    public CdcClusterInfoDTO clusterInfo() {
        CdcClusterInfoDTO dto = new CdcClusterInfoDTO();
        try {
            Map<String, Object> overview = flinkJobService.getClusterOverview();
            dto.setSlotsTotal(toInteger(overview == null ? null : overview.get("slots-total")));
            dto.setSlotsAvailable(toInteger(overview == null ? null : overview.get("slots-available")));
        } catch (Exception e) {
            logger.warn("查询 Flink 集群概览失败（前端降级通用提示）: error={}", e.getMessage());
        }
        return dto;
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    /**
     * 管道实时 KPI（Sprint 9 F1 运行监控页签）。
     * <p>
     * RUNNING：实时拉取 Flink live 指标（吞吐/重启）并刷新延迟/累计变更；
     * 非 RUNNING 或实时查询失败：返回 DB 最后已知值 + live=false（吞吐/重启标记 -1/0，前端标注「已停止」）。
     */
    public CdcPipelineMetricsDTO.Current metricsCurrent(Long id) {
        CdcPipeline entity = getPipeline(id);
        CdcPipelineMetricsDTO.Current dto = new CdcPipelineMetricsDTO.Current();
        boolean running = CdcPipeline.STATUS_RUNNING.equals(entity.getStatus())
                && entity.getFlinkJobId() != null;
        dto.setLive(running);
        dto.setCurrentLagSeconds(entity.getCurrentLagSeconds());
        dto.setTotalChanges(entity.getTotalChanges());
        if (!running) {
            dto.setThroughputRowsPerSecond(-1.0);
            dto.setNumRestarts(0);
            return dto;
        }
        String jobId = entity.getFlinkJobId();
        try {
            Map<String, Object> overview = flinkJobService.getJobOverview(jobId);
            dto.setThroughputRowsPerSecond(flinkJobService.extractThroughput(jobId, overview));
            // 延迟/累计变更以实时值为准（-1 表示取不到，保留 DB 最后已知值）
            long[] metrics = flinkJobService.extractMetrics(jobId, overview);
            if (metrics[0] >= 0) {
                dto.setCurrentLagSeconds((int) metrics[0]);
            }
            if (metrics[1] >= 0) {
                dto.setTotalChanges(metrics[1]);
            }
        } catch (Exception e) {
            logger.warn("查询管道实时 KPI 失败（返回最后已知值）: pipelineId={}, error={}", id, e.getMessage());
            dto.setLive(false);
            dto.setThroughputRowsPerSecond(-1.0);
            dto.setNumRestarts(0);
            return dto;
        }
        try {
            Map<String, Double> jobMetrics = flinkJobService.getJobMetrics(jobId, List.of("numRestarts"));
            Double restarts = jobMetrics.get("numRestarts");
            dto.setNumRestarts(restarts == null ? 0 : (int) Math.round(restarts));
        } catch (Exception e) {
            logger.debug("查询 Flink job numRestarts 失败: pipelineId={}, error={}", id, e.getMessage());
            dto.setNumRestarts(0);
        }
        return dto;
    }

    /**
     * 管道指标趋势（Sprint 9 F1 运行监控页签）。
     * <p>
     * range：1h/6h 返回原始分钟点；24h 按 5 分钟桶；7d 按小时桶（date_trunc + avg/max 聚合）。
     * 无样本时段返回空（前端断点展示，不插值造假，对齐 PRD「无数据时段断点」）。
     */
    public CdcPipelineMetricsDTO.Trend metricsTrend(Long id, String range) {
        getPipeline(id);
        String r = range == null || range.isBlank() ? "24h" : range;
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from;
        String bucketSql;
        switch (r) {
            case "1h" -> {
                from = to.minusHours(1);
                bucketSql = "date_trunc('minute', minute_at)";
            }
            case "6h" -> {
                from = to.minusHours(6);
                bucketSql = "date_trunc('minute', minute_at)";
            }
            case "7d" -> {
                from = to.minusDays(7);
                bucketSql = "date_trunc('hour', minute_at)";
            }
            default -> {
                // 24h：5 分钟桶（date_trunc 到分钟后再减去「分钟数对 5 取模」的分钟数，对齐桶起点，如 13:58→13:55）
                r = "24h";
                from = to.minusHours(24);
                bucketSql = "date_trunc('minute', minute_at) - (EXTRACT(MINUTE FROM minute_at)::int % 5) * INTERVAL '1 minute'";
            }
        }
        List<Map<String, Object>> rows = metricMinuteMapper.selectTrend(id, bucketSql, from, to);
        CdcPipelineMetricsDTO.Trend trend = new CdcPipelineMetricsDTO.Trend();
        trend.setRange(r);
        trend.setPoints(rows.stream().map(row -> {
            CdcPipelineMetricsDTO.TrendPoint point = new CdcPipelineMetricsDTO.TrendPoint();
            Object minuteAt = row.get("minute_at");
            if (minuteAt instanceof java.sql.Timestamp ts) {
                point.setMinuteAt(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(ts.toLocalDateTime()));
            } else if (minuteAt instanceof LocalDateTime ldt) {
                point.setMinuteAt(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(ldt));
            }
            point.setLagAvgSeconds(toIntegerOrNull(row.get("lag_avg_seconds")));
            point.setLagMaxSeconds(toIntegerOrNull(row.get("lag_max_seconds")));
            Object throughput = row.get("records_per_second_avg");
            point.setRecordsPerSecondAvg(throughput == null ? null : ((Number) throughput).doubleValue());
            return point;
        }).toList());
        return trend;
    }

    private static Integer toIntegerOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
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
            // F4：主管道启动成功后，尝试启动事件作业（best effort，失败不阻断主管道）
            startEventJob(id, entity, datasource, tables);
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
        String oldSavepoint = entity.getSavepointPath();
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

        // F4：主管道停止成功后，尝试停止事件作业（best effort，失败不阻断）
        stopEventJob(id, entity.getCdcEventsFlinkJobId());

        // Sprint 9 F2（T3）：停止生成新 savepoint 替换旧路径时，旧文件已失效，物理清理。
        // Review 2026-08-11 修复：挪到 DB 更新成功后（防 update 失败后旧路径仍指向已删文件）。
        if (oldSavepoint != null && !oldSavepoint.isBlank() && !oldSavepoint.equals(savepointPath)) {
            boolean cleaned = savepointFileCleaner.deleteSavepoint(oldSavepoint);
            if (!cleaned) {
                writeLog(id, CdcPipelineLog.LEVEL_WARN,
                        "停止时清理旧 savepoint 文件失败（文件可能残留，请运维侧处理）: " + oldSavepoint);
            }
        }
        return detail(id);
    }

    /**
     * 管道 checkpoint 历史/健康度（Sprint 9 F2 检查点页签，T5：实时转发 Flink REST 不落库）。
     * <p>
     * 非 RUNNING 或查询失败返回 reachable=false 空结构，前端降级提示。
     */
    public CdcPipelineCheckpointsDTO.Checkpoints checkpoints(Long id) {
        CdcPipeline entity = getPipeline(id);
        CdcPipelineCheckpointsDTO.Checkpoints dto = new CdcPipelineCheckpointsDTO.Checkpoints();
        if (!CdcPipeline.STATUS_RUNNING.equals(entity.getStatus()) || entity.getFlinkJobId() == null) {
            dto.setReachable(false);
            return dto;
        }
        try {
            Map<String, Object> raw = flinkJobService.getCheckpoints(entity.getFlinkJobId());
            dto.setReachable(true);
            fillCheckpointSummary(dto, raw);
            fillCheckpointHistory(dto, raw);
            dto.setLatestSavepointPath(extractLatestSavepointPath(raw));
            return dto;
        } catch (Exception e) {
            logger.warn("查询管道 checkpoint 失败（返回不可达）: pipelineId={}, error={}", id, e.getMessage());
            dto.setReachable(false);
            return dto;
        }
    }

    /**
     * 手动触发 savepoint（Sprint 9 F2，§4.3）。
     * <p>
     * 仅 RUNNING 可触发（8011）；触发成功回写 savepoint_path，替换时同步清理旧 savepoint 文件（T3）；
     * 失败/超时抛 8010。
     */
    public CdcPipelineCheckpointsDTO.SavepointResult triggerSavepoint(Long id) {
        CdcPipeline entity = getPipeline(id);
        if (!CdcPipeline.STATUS_RUNNING.equals(entity.getStatus()) || entity.getFlinkJobId() == null) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_NOT_RUNNING, "仅运行中管道可触发 savepoint");
        }
        String newPath;
        try {
            newPath = flinkJobService.triggerSavepoint(entity.getFlinkJobId());
        } catch (Exception e) {
            logger.error("CDC 手动触发 savepoint 失败: pipelineId={}, flinkJobId={}", id, entity.getFlinkJobId(), e);
            writeLog(id, CdcPipelineLog.LEVEL_ERROR, "手动触发 savepoint 失败: " + e.getMessage());
            throw new BusinessException(ErrorCode.CDC_SAVEPOINT_TRIGGER_FAILED, e.getMessage());
        }
        // 替换语义：旧 savepoint 已失效，物理清理（失败 warn 留痕不阻断）。
        // Review 2026-08-11 修复：挪到 DB 更新成功后（防 update 失败后旧路径仍指向已删文件）。
        String oldPath = entity.getSavepointPath();
        pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                .eq("id", id)
                .set("savepoint_path", newPath)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now()));
        if (oldPath != null && !oldPath.isBlank() && !oldPath.equals(newPath)) {
            boolean cleaned = savepointFileCleaner.deleteSavepoint(oldPath);
            if (!cleaned) {
                writeLog(id, CdcPipelineLog.LEVEL_WARN,
                        "手动 savepoint 替换时清理旧文件失败（文件可能残留，请运维侧处理）: " + oldPath);
            }
        }
        writeLog(id, CdcPipelineLog.LEVEL_INFO, "手动触发 savepoint 成功: " + newPath);
        CdcPipelineCheckpointsDTO.SavepointResult result = new CdcPipelineCheckpointsDTO.SavepointResult();
        result.setSavepointPath(newPath);
        return result;
    }

    /**
     * 强制停止（Sprint 9 F2，§4.2 降级路径）：作业已丢失时跳过 savepoint，置 STOPPED + 清
     * flink_job_id/savepoint_path + INFO 日志。CAS 更新（WHERE status='RUNNING'）防与 stop 竞态；
     * 非 RUNNING 幂等返回当前状态。
     */
    public CdcPipelineDTO forceStop(Long id) {
        CdcPipeline entity = getPipeline(id);
        if (!CdcPipeline.STATUS_RUNNING.equals(entity.getStatus())) {
            // 幂等：非 RUNNING 直接返回当前状态
            return detail(id);
        }
        String oldSavepoint = entity.getSavepointPath();
        int updated = pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                .eq("id", id)
                .eq("status", CdcPipeline.STATUS_RUNNING)
                .set("status", CdcPipeline.STATUS_STOPPED)
                .set("flink_job_id", null)
                .set("savepoint_path", null)
                .set("current_lag_seconds", null)
                .set("cdc_events_flink_job_id", null)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now()));
        if (updated == 0) {
            // CAS 失败：状态已被并发修改（如 stop 已成功），返回当前状态
            logger.info("强制停止 CAS 未命中（状态已变化），返回当前状态: pipelineId={}", id);
            return detail(id);
        }
        // F4：强制停止同步停事件作业（best effort，实际 cancel + 日志；DB 已在上方清 cdc_events_flink_job_id）
        stopEventJob(id, entity.getCdcEventsFlinkJobId());
        // 清理 savepoint 文件（强制停止语义：未保存位点，下次启动按启动位点重新同步）。
        // Review 2026-08-11 修复：挪到 DB 更新成功后。
        if (oldSavepoint != null && !oldSavepoint.isBlank()) {
            boolean cleaned = savepointFileCleaner.deleteSavepoint(oldSavepoint);
            if (!cleaned) {
                writeLog(id, CdcPipelineLog.LEVEL_WARN,
                        "强制停止清理 savepoint 文件失败（文件可能残留，请运维侧处理）: " + oldSavepoint);
            }
        }
        writeLog(id, CdcPipelineLog.LEVEL_INFO,
                "管道强制停止成功（作业已丢失，未保存位点；下次启动将按启动位点重新同步）");
        return detail(id);
    }

    /**
     * 启动事件作业（F4 WebSocket 实时订阅：Kafka 单 sink 事件管道，latest-offset 仅增量）。
     * <p>
     * best effort：失败不阻断主管道启动，仅记 WARN 日志（cdc_events_flink_job_id 保持 null）。
     */
    private void startEventJob(Long pipelineId, CdcPipeline entity, DataSourceInfo datasource,
                               List<CdcPipelineTable> tables) {
        try {
            String eventYaml = yamlBuilder.buildEvent(entity, tables, datasource.getType(), datasource.getHost(),
                    datasource.getPort(), datasource.getUsername(), precheckService.decryptPassword(datasource));
            String eventJobId = flinkJobService.submit(eventYaml, null, null);
            pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                    .eq("id", pipelineId)
                    .set("cdc_events_flink_job_id", eventJobId)
                    .set("updated_by", currentUserId())
                    .set("updated_at", LocalDateTime.now()));
            writeLog(pipelineId, CdcPipelineLog.LEVEL_INFO,
                    "事件作业启动成功（Kafka 实时订阅），Flink 作业 ID: " + eventJobId);
        } catch (Exception e) {
            logger.warn("CDC 事件作业启动失败（不影响主管道）: pipelineId={}, error={}",
                    pipelineId, e.getMessage());
            writeLog(pipelineId, CdcPipelineLog.LEVEL_WARN,
                    "事件作业启动失败（不影响主管道同步）: " + truncate(e.getMessage(), 2000));
        }
    }

    /**
     * 停止事件作业（cancel 不做 savepoint）。best effort：失败不阻断主管道停止，仅记 WARN 日志。
     */
    private void stopEventJob(Long pipelineId, String eventJobId) {
        if (eventJobId == null || eventJobId.isBlank()) {
            return;
        }
        try {
            flinkJobService.cancelJob(eventJobId);
            pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                    .eq("id", pipelineId)
                    .set("cdc_events_flink_job_id", null)
                    .set("updated_by", currentUserId())
                    .set("updated_at", LocalDateTime.now()));
            writeLog(pipelineId, CdcPipelineLog.LEVEL_INFO, "事件作业已停止");
        } catch (Exception e) {
            logger.warn("CDC 事件作业停止失败（不影响主管道）: pipelineId={}, error={}",
                    pipelineId, e.getMessage());
            writeLog(pipelineId, CdcPipelineLog.LEVEL_WARN,
                    "事件作业停止失败（可能残留，请运维侧处理）: " + truncate(e.getMessage(), 2000));
        }
    }

    private void fillCheckpointSummary(CdcPipelineCheckpointsDTO.Checkpoints dto, Map<String, Object> raw) {
        CdcPipelineCheckpointsDTO.Summary summary = new CdcPipelineCheckpointsDTO.Summary();
        Map<String, Object> counts = asMap(raw.get("counts"));
        Object failed = counts == null ? null : counts.get("failed");
        summary.setRecentFailedCount(failed == null ? 0L : ((Number) failed).longValue());
        Map<String, Object> summaryRaw = asMap(raw.get("summary"));
        Map<String, Object> duration = summaryRaw == null ? null : asMap(summaryRaw.get("end_to_end_duration"));
        Object avg = duration == null ? null : duration.get("avg");
        summary.setAvgDurationMs(avg == null ? null : ((Number) avg).longValue());
        Map<String, Object> latest = asMap(raw.get("latest"));
        Map<String, Object> completed = latest == null ? null : asMap(latest.get("completed"));
        Object triggerTs = completed == null ? null : completed.get("trigger_timestamp");
        if (triggerTs != null) {
            summary.setLatestCompletedTime(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.Instant.ofEpochMilli(((Number) triggerTs).longValue())
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()));
        }
        dto.setSummary(summary);
    }

    private void fillCheckpointHistory(CdcPipelineCheckpointsDTO.Checkpoints dto, Map<String, Object> raw) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) raw.get("history");
        if (history == null || history.isEmpty()) {
            dto.setHistory(List.of());
            return;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        dto.setHistory(history.stream().limit(20).map(item -> {
            CdcPipelineCheckpointsDTO.HistoryItem h = new CdcPipelineCheckpointsDTO.HistoryItem();
            Object triggerTs = item.get("trigger_timestamp");
            if (triggerTs != null) {
                h.setTriggerTime(formatter.format(java.time.Instant.ofEpochMilli(((Number) triggerTs).longValue())
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()));
            }
            Object duration = item.get("end_to_end_duration");
            h.setDurationMs(duration == null ? null : ((Number) duration).longValue());
            Object size = item.get("state_size");
            h.setStateSizeBytes(size == null ? null : ((Number) size).longValue());
            Object status = item.get("status");
            h.setStatus(status == null ? null : String.valueOf(status));
            Object isSavepoint = item.get("is_savepoint");
            h.setSavepoint(isSavepoint != null && Boolean.parseBoolean(String.valueOf(isSavepoint)));
            Object type = item.get("checkpoint_type");
            h.setCheckpointType(type == null ? null : String.valueOf(type));
            return h;
        }).toList());
    }

    private String extractLatestSavepointPath(Map<String, Object> raw) {
        Map<String, Object> latest = asMap(raw.get("latest"));
        Map<String, Object> savepoint = latest == null ? null : asMap(latest.get("savepoint"));
        Object path = savepoint == null ? null : savepoint.get("external_path");
        return path == null ? null : String.valueOf(path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /**
     * 事务提交后清理 savepoint 文件（Review 2026-08-11 修复）。
     * <p>
     * S3 清理不得早于 DB 提交：若在事务内先删文件、后 DB 回滚，savepoint_path 仍指向已删文件，
     * 下次启动恢复失败；且 S3 调用（网络 IO）不应持有 DB 事务连接。
     * 非事务调用路径（stop/forceStop/triggerSavepoint 的单条 update 已提交）直接清理。
     * 清理失败 warn 留痕，不阻断主流程（R5）。
     */
    private void afterCommitCleanSavepoint(Long pipelineId, String savepointPath, String failLogPrefix) {
        Runnable cleanup = () -> {
            boolean cleaned = savepointFileCleaner.deleteSavepoint(savepointPath);
            if (!cleaned) {
                writeLog(pipelineId, CdcPipelineLog.LEVEL_WARN, failLogPrefix + ": " + savepointPath);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
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

    /**
     * 条件刷新 Doris 湖仓 catalog（internal 契约，app-job 定时触发，2026-08-11 验收反馈）：
     * 仅当存在 RUNNING 管道时才 REFRESH CATALOG（无运行管道不空转 Doris）。
     *
     * @return true = 本轮实际执行了刷新
     */
    public boolean refreshCatalogIfRunning() {        Long running = pipelineMapper.selectCount(
                new QueryWrapper<CdcPipeline>().eq("status", CdcPipeline.STATUS_RUNNING));
        if (running == null || running == 0) {
            return false;
        }
        dorisCatalogService.refreshCatalog();
        return true;
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

    /**
     * 批量查询管道 id → name（internal 契约，Sprint 9 F3：app-alert 告警对象名反查/可选对象下拉）。
     * ids 为空时返回全部管道（对象下拉场景），供 app-alert 列表展示。
     */
    public Map<Long, String> names(List<Long> ids) {
        QueryWrapper<CdcPipeline> wrapper = new QueryWrapper<>();
        if (ids != null && !ids.isEmpty()) {
            wrapper.in("id", ids);
        }
        return pipelineMapper.selectList(wrapper)
                .stream()
                .collect(Collectors.toMap(CdcPipeline::getId, CdcPipeline::getName, (a, b) -> a));
    }

    /** 管道订阅信息（F4 WebSocket 订阅校验：状态 + 源数据源/库 + 源表清单，供数据服务反查敏感度） */
    public CdcPipelineSubscribeDTO subscribeInfo(Long id) {
        CdcPipeline entity = getPipeline(id);
        CdcPipelineSubscribeDTO dto = new CdcPipelineSubscribeDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setSourceDatasourceId(entity.getSourceDatasourceId());
        dto.setSourceDatabase(entity.getSourceDatabase());
        dto.setSourceTables(listTables(id).stream().map(CdcTableMappingDTO::getSourceTable).toList());
        return dto;
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
