package com.datanest.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.datanest.governance.dto.QualityIssueItemDTO;
import com.datanest.governance.dto.QualityLevelTrendPointDTO;
import com.datanest.governance.dto.DatasourceScoreComparisonDTO;
import com.datanest.governance.dto.QualityReportOptionsDTO;
import com.datanest.governance.dto.QualityReportRequest;
import com.datanest.governance.dto.QualityReportSummaryDTO;
import com.datanest.governance.dto.QualityScoreDistributionDTO;
import com.datanest.governance.dto.QualityScoreTrendPointDTO;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.entity.QualityCheckDetail;
import com.datanest.governance.entity.QualityJob;
import com.datanest.governance.entity.QualityRule;
import com.datanest.governance.entity.QualityScore;
import com.datanest.governance.entity.QualityScoreHistory;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.governance.mapper.QualityCheckDetailMapper;
import com.datanest.governance.mapper.QualityJobMapper;
import com.datanest.governance.mapper.QualityRuleMapper;
import com.datanest.governance.mapper.QualityScoreHistoryMapper;
import com.datanest.governance.mapper.QualityScoreMapper;
import com.datanest.governance.util.CsvExportHelper;
import org.apache.commons.csv.CSVPrinter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 质量报告服务（Sprint 8 F3，DG-07 完整版）。
 * <p>
 * 多维筛选（数据源/库/质量任务/时间范围）+ KPI 汇总 + 四档分布趋势 + 表评分趋势 + 问题清单 + CSV 导出。
 * 全部治理库本地聚合（quality_check_detail/batch、quality_score/history、metadata_table），
 * 数据源名称经 engineering-api 批量回填（失败降级 id 占位，不阻断报告）。
 * 口径：批次数 = 范围内有明细的 distinct batch_id（按库/数据源筛选时与明细同口径）；
 * 平均评分 = 范围内表当前最新评分均值（与时间/任务无关）；通过率 = PASS / 有效明细（排除 UNAVAILABLE）。
 */
@Slf4j
@Service
public class QualityReportService {

    /** 默认时间范围：最近 30 天（startTime/endTime 均未传时） */
    private static final int DEFAULT_RANGE_DAYS = 30;
    /** 导出问题清单行数上限（超出截断 + warn，对齐收藏导出模式） */
    private static final int MAX_EXPORT_ROWS = 5000;
    private static final long BUILTIN_DORIS_DATASOURCE_ID = -1L;
    private static final String BUILTIN_DORIS_NAME = "Doris 数仓";
    private static final DateTimeFormatter CSV_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QualityCheckDetailMapper detailMapper;
    private final QualityScoreMapper scoreMapper;
    private final QualityScoreHistoryMapper scoreHistoryMapper;
    private final QualityRuleMapper ruleMapper;
    private final QualityJobMapper jobMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final EngineeringDatasourceApi datasourceApi;

    public QualityReportService(QualityCheckDetailMapper detailMapper,
                                QualityScoreMapper scoreMapper,
                                QualityScoreHistoryMapper scoreHistoryMapper,
                                QualityRuleMapper ruleMapper,
                                QualityJobMapper jobMapper,
                                MetadataTableMapper metadataTableMapper,
                                EngineeringDatasourceApi datasourceApi) {
        this.detailMapper = detailMapper;
        this.scoreMapper = scoreMapper;
        this.scoreHistoryMapper = scoreHistoryMapper;
        this.ruleMapper = ruleMapper;
        this.jobMapper = jobMapper;
        this.metadataTableMapper = metadataTableMapper;
        this.datasourceApi = datasourceApi;
    }

    // ==================== 筛选联动选项 ====================

    /** 筛选选项：数据源（metadata_table distinct + 名称回填）/ 库（随数据源联动）/ 质量任务。 */
    public QualityReportOptionsDTO options(Long datasourceId) {
        QualityReportOptionsDTO dto = new QualityReportOptionsDTO();

        List<Long> dsIds = metadataTableMapper.selectObjs(new QueryWrapper<MetadataTable>()
                        .select("DISTINCT datasource_id"))
                .stream().filter(Objects::nonNull).map(o -> ((Number) o).longValue()).sorted().toList();
        Map<Long, String> nameMap = datasourceNames(dsIds);
        dto.setDatasources(dsIds.stream().map(id -> {
            QualityReportOptionsDTO.Option option = new QualityReportOptionsDTO.Option();
            option.setId(id);
            option.setName(nameMap.getOrDefault(id, "数据源 " + id));
            return option;
        }).toList());

        QueryWrapper<MetadataTable> dbWrapper = new QueryWrapper<MetadataTable>()
                .select("DISTINCT database_name", "datasource_id").isNotNull("database_name");
        if (datasourceId != null) {
            dbWrapper.eq("datasource_id", datasourceId);
        }
        dto.setDatabases(metadataTableMapper.selectMaps(dbWrapper).stream()
                .map(row -> {
                    QualityReportOptionsDTO.DatabaseOption option = new QualityReportOptionsDTO.DatabaseOption();
                    option.setName(String.valueOf(row.get("database_name")));
                    Object dsId = row.get("datasource_id");
                    option.setDatasourceId(dsId == null ? null : ((Number) dsId).longValue());
                    return option;
                })
                .sorted(Comparator.comparing(QualityReportOptionsDTO.DatabaseOption::getName))
                .toList());

        // 质量任务随数据源联动：只列规则覆盖该数据源表的任务（经 quality_rule → metadata_table 反查）
        QueryWrapper<QualityJob> jobWrapper = new QueryWrapper<QualityJob>()
                .select("id", "name").orderByAsc("name");
        if (datasourceId != null) {
            jobWrapper.inSql("id", "SELECT DISTINCT job_id FROM quality_rule WHERE table_id IN "
                    + "(SELECT id FROM metadata_table WHERE datasource_id = " + datasourceId + ")");
        }
        dto.setJobs(jobMapper.selectList(jobWrapper)
                .stream().map(j -> {
                    QualityReportOptionsDTO.Option option = new QualityReportOptionsDTO.Option();
                    option.setId(j.getId());
                    option.setName(j.getName());
                    return option;
                }).toList());
        return dto;
    }

    // ==================== KPI 汇总 ====================

    public QualityReportSummaryDTO summary(QualityReportRequest request) {
        LocalDateTime[] range = resolveRange(request);
        FilterTables filter = resolveTableIds(request);
        QualityReportSummaryDTO dto = new QualityReportSummaryDTO();
        if (filter.emptyResult) {
            dto.setBatchCount(0L);
            dto.setDetailCount(0L);
            dto.setSevereCount(0L);
            dto.setWarningCount(0L);
            return dto;
        }
        Map<String, Object> row = detailMapper.selectReportSummary(range[0], range[1], filter.tableIds,
                request == null ? null : request.getJobId());
        long detailCount = number(row, "detail_count");
        long passCount = number(row, "pass_count");
        long validCount = number(row, "valid_count");
        dto.setBatchCount(number(row, "batch_count"));
        dto.setDetailCount(detailCount);
        dto.setPassRate(validCount == 0 ? null
                : BigDecimal.valueOf(passCount * 100.0 / validCount).setScale(2, RoundingMode.HALF_UP));
        // 待处理问题计数（KPI 第 5 卡，2026-08-11 前端联调确认补齐）
        dto.setSevereCount(number(row, "severe_count"));
        dto.setWarningCount(number(row, "warning_count"));

        // 平均评分：范围内 ONLINE 表当前最新评分均值（quality_score 当前值，与时间无关）；
        // 筛选口径（数据源/库/质量任务）统一走 resolveScoreScopeTableIds（2026-08-11 Review 口径统一：
        // 三处评分聚合都只统计 ONLINE 表，OFFLINE 残留评分不计）
        List<Long> avgScope = resolveScoreScopeTableIds(request);
        if (avgScope != null && avgScope.isEmpty()) {
            return dto;
        }
        QueryWrapper<QualityScore> scoreWrapper = new QueryWrapper<QualityScore>().select("score");
        applyOnlineScoreScope(scoreWrapper, avgScope);
        List<QualityScore> scores = scoreMapper.selectList(scoreWrapper);
        if (!scores.isEmpty()) {
            BigDecimal sum = scores.stream().map(QualityScore::getScore)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            dto.setAvgScore(sum.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP));
        }
        return dto;
    }

    /** 评分查询统一收窄到 ONLINE 表（scope 非空时已是 ONLINE 集合；null 时用子查询限定 ONLINE）。 */
    private void applyOnlineScoreScope(QueryWrapper<QualityScore> wrapper, List<Long> scope) {
        if (scope != null) {
            wrapper.in("table_id", scope);
        } else {
            wrapper.inSql("table_id", "SELECT id FROM metadata_table WHERE source_status = 'ONLINE'");
        }
    }

    // ==================== 表评分分布（环图） ====================

    /**
     * 表评分分布：范围内 ONLINE 表的当前最新评分按健康度计数 + 无评分表数（2026-08-11 前端联调确认新增）。
     * 与时间无关（当前评分）；筛选口径对齐平均评分（数据源/库/质量任务收窄）。
     */
    public QualityScoreDistributionDTO scoreDistribution(QualityReportRequest request) {
        List<Long> scope = resolveScoreScopeTableIds(request);
        QualityScoreDistributionDTO dto = new QualityScoreDistributionDTO();
        if (scope != null && scope.isEmpty()) {
            // 有筛选条件但无命中表：全零
            dto.setExcellentCount(0L);
            dto.setGoodCount(0L);
            dto.setWarningCount(0L);
            dto.setBadCount(0L);
            dto.setNoScoreCount(0L);
            dto.setTotalTables(0L);
            return dto;
        }
        QueryWrapper<MetadataTable> totalWrapper = new QueryWrapper<MetadataTable>()
                .eq("source_status", "ONLINE");
        if (scope != null) {
            totalWrapper.in("id", scope);
        }
        Long total = metadataTableMapper.selectCount(totalWrapper);
        long totalTables = total == null ? 0L : total;

        QueryWrapper<QualityScore> scoreWrapper = new QueryWrapper<QualityScore>()
                .select("health_level", "COUNT(*) AS cnt")
                .groupBy("health_level");
        applyOnlineScoreScope(scoreWrapper, scope);
        Map<String, Long> byLevel = new HashMap<>();
        for (Map<String, Object> row : scoreMapper.selectMaps(scoreWrapper)) {
            Object level = row.get("health_level");
            if (level != null) {
                byLevel.put((String) level, ((Number) row.get("cnt")).longValue());
            }
        }
        long excellent = byLevel.getOrDefault("EXCELLENT", 0L);
        long good = byLevel.getOrDefault("GOOD", 0L);
        long warning = byLevel.getOrDefault("WARNING", 0L);
        long bad = byLevel.getOrDefault("BAD", 0L);
        dto.setExcellentCount(excellent);
        dto.setGoodCount(good);
        dto.setWarningCount(warning);
        dto.setBadCount(bad);
        dto.setTotalTables(totalTables);
        dto.setNoScoreCount(Math.max(0, totalTables - excellent - good - warning - bad));
        return dto;
    }

    // ==================== 数据源质量对比 ====================

    /** 数据源质量对比：按数据源分组平均评分（当前最新评分 ⋈ ONLINE 表），按均分降序，回填数据源名。 */
    public List<DatasourceScoreComparisonDTO> datasourceComparison(QualityReportRequest request) {
        List<Long> scope = resolveScoreScopeTableIds(request);
        if (scope != null && scope.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = scoreMapper.selectDatasourceScoreComparison(scope);
        List<Long> dsIds = rows.stream()
                .map(row -> ((Number) row.get("datasource_id")).longValue()).toList();
        Map<Long, String> names = datasourceNames(dsIds);
        return rows.stream().map(row -> {
            DatasourceScoreComparisonDTO dto = new DatasourceScoreComparisonDTO();
            Long dsId = ((Number) row.get("datasource_id")).longValue();
            dto.setDatasourceId(dsId);
            dto.setDatasourceName(names.getOrDefault(dsId, "数据源 " + dsId));
            Object avg = row.get("avg_score");
            if (avg != null) {
                dto.setAvgScore(new BigDecimal(avg.toString()).setScale(1, RoundingMode.HALF_UP));
            }
            dto.setTableCount(((Number) row.get("table_count")).longValue());
            return dto;
        }).toList();
    }

    /**
     * 评分口径的表范围（数据源/库筛选 + 质量任务规则覆盖表收窄，统一只含 ONLINE 表；
     * null = 不按表过滤（调用方用 ONLINE 子查询兜底），空列表 = 有筛选条件但无命中）。
     */
    private List<Long> resolveScoreScopeTableIds(QualityReportRequest request) {
        Long datasourceId = request == null ? null : request.getDatasourceId();
        String databaseName = request == null || request.getDatabaseName() == null
                || request.getDatabaseName().isBlank() ? null : request.getDatabaseName().trim();
        Long jobId = request == null ? null : request.getJobId();
        List<Long> tableIds = null;
        if (datasourceId != null || databaseName != null) {
            QueryWrapper<MetadataTable> wrapper = new QueryWrapper<MetadataTable>().select("id")
                    .eq("source_status", "ONLINE");
            if (datasourceId != null) {
                wrapper.eq("datasource_id", datasourceId);
            }
            if (databaseName != null) {
                wrapper.eq("database_name", databaseName);
            }
            tableIds = metadataTableMapper.selectList(wrapper)
                    .stream().map(MetadataTable::getId).toList();
        }
        if (jobId != null) {
            List<Long> jobTableIds = ruleMapper.selectObjs(new QueryWrapper<QualityRule>()
                            .select("DISTINCT table_id").eq("job_id", jobId))
                    .stream().filter(Objects::nonNull).map(o -> ((Number) o).longValue()).toList();
            // 任务覆盖表同样只保留 ONLINE（OFFLINE 残留评分不计入评分口径）
            List<Long> onlineJobTableIds = jobTableIds.isEmpty() ? List.of()
                    : metadataTableMapper.selectList(new QueryWrapper<MetadataTable>().select("id")
                                    .eq("source_status", "ONLINE").in("id", jobTableIds))
                            .stream().map(MetadataTable::getId).toList();
            tableIds = tableIds == null ? onlineJobTableIds
                    : tableIds.stream().filter(onlineJobTableIds::contains).toList();
        }
        return tableIds;
    }

    // ==================== 四档分布趋势 ====================

    public List<QualityLevelTrendPointDTO> levelTrend(QualityReportRequest request) {
        LocalDateTime[] range = resolveRange(request);
        FilterTables filter = resolveTableIds(request);
        if (filter.emptyResult) {
            return List.of();
        }
        return detailMapper.selectDailyLevelTrend(range[0], range[1], filter.tableIds,
                        request == null ? null : request.getJobId())
                .stream().map(row -> {
                    QualityLevelTrendPointDTO point = new QualityLevelTrendPointDTO();
                    point.setDay((String) row.get("day"));
                    point.setPassCount(number(row, "pass_count"));
                    point.setWarningCount(number(row, "warning_count"));
                    point.setSevereCount(number(row, "severe_count"));
                    point.setUnavailableCount(number(row, "unavailable_count"));
                    return point;
                }).toList();
    }

    // ==================== 评分趋势 ====================

    /**
     * 评分趋势：tableId 为空 = 聚合模式（按天 AVG(score)，口径对齐评分聚合：ONLINE 表 + 数据源/库/任务收窄）；
     * tableId 非空 = 单表模式（该表评分历史序列，保留兼容）。
     */
    public List<QualityScoreTrendPointDTO> scoreTrend(QualityReportRequest request) {
        LocalDateTime[] range = resolveRange(request);
        if (request != null && request.getTableId() != null) {
            if (metadataTableMapper.selectById(request.getTableId()) == null) {
                throw new BusinessException(ErrorCode.QUALITY_REPORT_PARAM_INVALID,
                        "表不存在：" + request.getTableId());
            }
            return scoreHistoryMapper.selectList(new QueryWrapper<QualityScoreHistory>()
                            .eq("table_id", request.getTableId())
                            .ge("checked_at", range[0])
                            .le("checked_at", range[1])
                            .orderByAsc("checked_at"))
                    .stream().map(h -> {
                        QualityScoreTrendPointDTO point = new QualityScoreTrendPointDTO();
                        point.setCheckedAt(h.getCheckedAt());
                        point.setScore(h.getScore());
                        point.setHealthLevel(h.getHealthLevel());
                        return point;
                    }).toList();
        }
        // 聚合模式：按天平均评分（报告定位是聚合视图，单表趋势归资产详情页）
        List<Long> scope = resolveScoreScopeTableIds(request);
        if (scope != null && scope.isEmpty()) {
            return List.of();
        }
        QueryWrapper<QualityScoreHistory> wrapper = new QueryWrapper<QualityScoreHistory>()
                .select("CAST(checked_at AS DATE) AS day",
                        "AVG(score) AS avg_score",
                        "COUNT(DISTINCT table_id) AS table_count")
                .ge("checked_at", range[0])
                .le("checked_at", range[1])
                .groupBy("CAST(checked_at AS DATE)")
                .orderByAsc("CAST(checked_at AS DATE)");
        if (scope != null) {
            wrapper.in("table_id", scope);
        } else {
            wrapper.inSql("table_id", "SELECT id FROM metadata_table WHERE source_status = 'ONLINE'");
        }
        return scoreHistoryMapper.selectMaps(wrapper).stream().map(row -> {
            QualityScoreTrendPointDTO point = new QualityScoreTrendPointDTO();
            point.setDay(String.valueOf(row.get("day")));
            Object avg = row.get("avg_score");
            if (avg != null) {
                point.setAvgScore(new BigDecimal(avg.toString()).setScale(2, RoundingMode.HALF_UP));
            }
            Object count = row.get("table_count");
            point.setTableCount(count == null ? 0L : ((Number) count).longValue());
            return point;
        }).toList();
    }

    // ==================== 问题清单 ====================

    public PageResult<QualityIssueItemDTO> issues(QualityReportRequest request) {
        LocalDateTime[] range = resolveRange(request);
        FilterTables filter = resolveTableIds(request);
        int page = request != null && request.getPage() != null && request.getPage() > 0 ? request.getPage() : 1;
        int pageSize = request != null && request.getPageSize() != null && request.getPageSize() > 0
                ? request.getPageSize() : 10;
        if (filter.emptyResult) {
            return new PageResult<>(List.of(), 0, page, pageSize);
        }
        QueryWrapper<QualityCheckDetail> wrapper = buildIssueWrapper(request, range, filter);
        IPage<QualityCheckDetail> mpPage = detailMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<QualityIssueItemDTO> items = toIssueItems(mpPage.getRecords());
        return new PageResult<>(items, mpPage.getTotal(), mpPage.getCurrent(), mpPage.getSize());
    }

    /** 问题清单公共查询条件（分页与导出共用）：WARNING/SEVERE + 筛选维度，id 倒序。 */
    private QueryWrapper<QualityCheckDetail> buildIssueWrapper(QualityReportRequest request,
                                                               LocalDateTime[] range, FilterTables filter) {
        QueryWrapper<QualityCheckDetail> wrapper = new QueryWrapper<QualityCheckDetail>()
                .in("result_level", "WARNING", "SEVERE")
                .ge("created_at", range[0])
                .le("created_at", range[1]);
        if (filter.tableIds != null) {
            wrapper.in("table_id", filter.tableIds);
        }
        Long jobId = request == null ? null : request.getJobId();
        if (jobId != null) {
            wrapper.inSql("batch_id", "SELECT id FROM quality_check_batch WHERE job_id = " + jobId);
        }
        wrapper.orderByDesc("id");
        return wrapper;
    }

    /** 明细 → 清单项：批量回填表名（metadata_table）与阈值（quality_rule，规则已删为 null）。 */
    private List<QualityIssueItemDTO> toIssueItems(List<QualityCheckDetail> details) {
        if (details.isEmpty()) {
            return List.of();
        }
        Map<Long, MetadataTable> tableMap = metadataTableMapper.selectBatchIds(
                        details.stream().map(QualityCheckDetail::getTableId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(MetadataTable::getId, Function.identity()));
        Map<Long, QualityRule> ruleMap = ruleMapper.selectBatchIds(
                        details.stream().map(QualityCheckDetail::getRuleId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(QualityRule::getId, Function.identity()));

        return details.stream().map(d -> {
            QualityIssueItemDTO dto = new QualityIssueItemDTO();
            dto.setDetailId(d.getId());
            dto.setTableId(d.getTableId());
            MetadataTable table = tableMap.get(d.getTableId());
            dto.setTableName(table == null ? null : qualifiedTableName(table));
            dto.setRuleId(d.getRuleId());
            dto.setRuleName(d.getRuleName());
            dto.setRuleType(d.getRuleType());
            dto.setResultMetric(d.getResultMetric());
            dto.setResultValue(d.getResultValue());
            QualityRule rule = ruleMap.get(d.getRuleId());
            if (rule != null) {
                dto.setThreshold("SEVERE".equals(d.getResultLevel())
                        ? rule.getSevereThreshold() : rule.getWarningThreshold());
            }
            dto.setResultLevel(d.getResultLevel());
            dto.setCheckedAt(d.getCreatedAt());
            return dto;
        }).toList();
    }

    // ==================== CSV 导出 ====================

    /**
     * 导出 CSV（UTF-8 with BOM，兼容 Excel；复用 Sprint 6 合规导出经验）：
     * 汇总 KPI + 当前筛选的问题清单全量（上限 MAX_EXPORT_ROWS 截断）。
     * 直写响应流（Controller 返回 void）：数据全部算完再开始写，
     * 写流前的参数/查询异常（4221/4222）仍由全局异常处理器返回 JSON 错误。
     */
    public void export(QualityReportRequest request, OutputStream out) {
        try {
            doExport(request, out);
        } catch (BusinessException e) {
            // 参数类错误（4221）原样抛出，不包成导出失败
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.QUALITY_REPORT_EXPORT_FAILED, e.getMessage());
        }
    }

    private void doExport(QualityReportRequest request, OutputStream out) throws IOException {
        QualityReportSummaryDTO summary = summary(request);
        LocalDateTime[] range = resolveRange(request);
        FilterTables filter = resolveTableIds(request);

        List<QualityCheckDetail> details;
        if (filter.emptyResult) {
            details = List.of();
        } else {
            details = detailMapper.selectList(buildIssueWrapper(request, range, filter)
                    .last("LIMIT " + (MAX_EXPORT_ROWS + 1)));
        }
        boolean truncated = details.size() > MAX_EXPORT_ROWS;
        if (truncated) {
            log.warn("质量报告导出问题清单达到上限 {}，已截断（实际更多）", MAX_EXPORT_ROWS);
            details = details.subList(0, MAX_EXPORT_ROWS);
        }
        List<QualityIssueItemDTO> issues = toIssueItems(details);

        // CSVPrinter 负责转义/引号；BOM 与公式注入防护由 CsvExportHelper 统一处理（只 flush 不 close）
        CSVPrinter printer = CsvExportHelper.printer(out);
        printer.printRecord("质量报告",
                CSV_TIME_FORMATTER.format(range[0]) + " ~ " + CSV_TIME_FORMATTER.format(range[1]));
        printer.printRecord("检查批次数", "规则明细数", "平均评分", "通过率(%)");
        printer.printRecord(summary.getBatchCount(), summary.getDetailCount(),
                summary.getAvgScore() == null ? "" : summary.getAvgScore(),
                summary.getPassRate() == null ? "" : summary.getPassRate());
        printer.printRecord();
        printer.printRecord("问题清单（WARNING/SEVERE）");
        printer.printRecord("表", "规则", "类型", "结果指标", "结果值", "阈值", "级别", "检查时间");
        for (QualityIssueItemDTO item : issues) {
            printer.printRecord(CsvExportHelper.safe(item.getTableName()),
                    CsvExportHelper.safe(item.getRuleName()),
                    CsvExportHelper.safe(item.getRuleType()),
                    CsvExportHelper.safe(item.getResultMetric()),
                    item.getResultValue() == null ? "" : item.getResultValue(),
                    item.getThreshold() == null ? "" : item.getThreshold(),
                    CsvExportHelper.safe(item.getResultLevel()),
                    item.getCheckedAt() == null ? "" : CSV_TIME_FORMATTER.format(item.getCheckedAt()));
        }
        if (truncated) {
            printer.printRecord("# 问题清单超过 " + MAX_EXPORT_ROWS + " 行，已截断");
        }
        printer.flush();
    }

    // ==================== 存量评分历史补算 ====================

    /**
     * 存量补算（B3，2026-08-11 用户确认手工触发）：为「有当前评分但无任何历史快照」的表
     * 从 quality_score 复制一条首次快照（checked_at 取 last_checked_at）。
     * quality_score 本就是 ScoreCalculator 算法对「各启用规则最近一次结果」的计算产物，
     * 与「从 quality_check_detail 按表取最近聚合」等价且口径一致。幂等：已有快照的表跳过。
     *
     * @return 本次补写的快照条数
     */
    public int backfillScoreHistory() {
        List<QualityScore> scores = scoreMapper.selectList(null);
        if (scores.isEmpty()) {
            return 0;
        }
        Set<Long> existingTableIds = new HashSet<>(scoreHistoryMapper.selectObjs(
                        new QueryWrapper<QualityScoreHistory>().select("DISTINCT table_id"))
                .stream().filter(Objects::nonNull).map(o -> ((Number) o).longValue()).toList());
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (QualityScore score : scores) {
            if (existingTableIds.contains(score.getTableId())) {
                continue;
            }
            QualityScoreHistory history = new QualityScoreHistory();
            history.setTableId(score.getTableId());
            history.setTableName(score.getTableName());
            history.setDatasourceId(score.getDatasourceId());
            history.setScore(score.getScore());
            history.setHealthLevel(score.getHealthLevel());
            history.setPassRules(score.getPassRules());
            history.setWarningRules(score.getWarningRules());
            history.setSevereRules(score.getSevereRules());
            history.setCheckedAt(score.getLastCheckedAt() == null ? now : score.getLastCheckedAt());
            history.setCreatedAt(now);
            scoreHistoryMapper.insert(history);
            count++;
        }
        log.info("存量评分历史补算完成: 补写 {} 条（当前评分表 {} 张，已有快照表 {} 张）",
                count, scores.size(), existingTableIds.size());
        return count;
    }

    // ==================== private ====================

    /** 筛选 → 表 ID 集合（datasourceId/databaseName 经 metadata_table 反查）；两者都空 = 不按表过滤。 */
    private FilterTables resolveTableIds(QualityReportRequest request) {
        Long datasourceId = request == null ? null : request.getDatasourceId();
        String databaseName = request == null || request.getDatabaseName() == null
                || request.getDatabaseName().isBlank() ? null : request.getDatabaseName().trim();
        if (datasourceId == null && databaseName == null) {
            return new FilterTables(null, false);
        }
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<MetadataTable>().select("id");
        if (datasourceId != null) {
            wrapper.eq("datasource_id", datasourceId);
        }
        if (databaseName != null) {
            wrapper.eq("database_name", databaseName);
        }
        List<Long> tableIds = metadataTableMapper.selectList(wrapper)
                .stream().map(MetadataTable::getId).toList();
        // 有筛选条件但无命中表：空结果（避免把空 IN 拼进 SQL）
        return new FilterTables(tableIds, tableIds.isEmpty());
    }

    /** 时间范围解析：ISO String → LocalDateTime；均空默认最近 30 天；非法格式/起止颠倒抛 4221。 */
    private LocalDateTime[] resolveRange(QualityReportRequest request) {
        LocalDateTime end = parseIso(request == null ? null : request.getEndTime());
        LocalDateTime start = parseIso(request == null ? null : request.getStartTime());
        if (end == null) {
            end = LocalDateTime.now();
        }
        if (start == null) {
            start = end.minusDays(DEFAULT_RANGE_DAYS);
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.QUALITY_REPORT_PARAM_INVALID, "开始时间不能晚于结束时间");
        }
        return new LocalDateTime[]{start, end};
    }

    private LocalDateTime parseIso(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.QUALITY_REPORT_PARAM_INVALID,
                    "时间格式非法（需 ISO 8601，如 2026-08-11T00:00:00）：" + value);
        }
    }

    /** 批量回填数据源名称（-1 内置 Doris 特判；失败经 RemoteCalls 降级空 Map，选项名退化为「数据源 id」）。 */
    private Map<Long, String> datasourceNames(List<Long> datasourceIds) {
        List<Long> externalIds = datasourceIds.stream()
                .filter(id -> id != BUILTIN_DORIS_DATASOURCE_ID).toList();
        Map<Long, String> nameMap = RemoteCalls.execute("engineering.datasource.batchGet", () -> {
            if (externalIds.isEmpty()) {
                return Map.<Long, String>of();
            }
            IdsRequest request = new IdsRequest();
            request.setIds(externalIds);
            Result<Map<Long, DataSourceInfo>> result = datasourceApi.batchGet(request);
            Map<Long, DataSourceInfo> data = result == null ? null : result.data();
            if (data == null) {
                return Map.<Long, String>of();
            }
            return data.values().stream()
                    .collect(Collectors.toMap(DataSourceInfo::getId, DataSourceInfo::getName, (a, b) -> a));
        }, Map.of());
        Map<Long, String> map = new HashMap<>(nameMap);
        if (datasourceIds.contains(BUILTIN_DORIS_DATASOURCE_ID)) {
            map.put(BUILTIN_DORIS_DATASOURCE_ID, BUILTIN_DORIS_NAME);
        }
        return map;
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** 组装库名.表名（schema 存在时优先用 schema，对齐 ScoreCalculator.qualifiedTableName）。 */
    private String qualifiedTableName(MetadataTable table) {
        if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
            return table.getSchemaName() + "." + table.getTableName();
        }
        if (table.getDatabaseName() != null && !table.getDatabaseName().isBlank()) {
            return table.getDatabaseName() + "." + table.getTableName();
        }
        return table.getTableName();
    }


    /** 表筛选结果：tableIds null = 不按表过滤；emptyResult = 有筛选条件但无命中（短路空结果）。 */
    private record FilterTables(List<Long> tableIds, boolean emptyResult) {
    }
}
