package com.datanest.dataservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.dataservice.dto.ApiCallLogItemDTO;
import com.datanest.dataservice.dto.ApiStatsDTO;
import com.datanest.dataservice.dto.CallStatAgg;
import com.datanest.dataservice.dto.OverviewAgg;
import com.datanest.dataservice.dto.RefCount;
import com.datanest.dataservice.dto.StatsErrorCodeDTO;
import com.datanest.dataservice.dto.StatsHealthDistributionDTO;
import com.datanest.dataservice.dto.StatsHealthItemDTO;
import com.datanest.dataservice.dto.StatsOverviewDTO;
import com.datanest.dataservice.dto.StatsTopApiDTO;
import com.datanest.dataservice.dto.StatsTopKeyDTO;
import com.datanest.dataservice.dto.StatusAgg;
import com.datanest.dataservice.dto.TrendAgg;
import com.datanest.dataservice.entity.ApiCallLog;
import com.datanest.dataservice.entity.ApiKey;
import com.datanest.dataservice.entity.DataApi;
import com.datanest.dataservice.mapper.ApiCallLogMapper;
import com.datanest.dataservice.mapper.ApiKeyBindingMapper;
import com.datanest.dataservice.mapper.ApiKeyMapper;
import com.datanest.dataservice.mapper.DataApiMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 全局调用统计服务（Sprint 10 F3，对齐技术文档 D-D8 异步写入 + 聚合查询）。
 * <p>
 * 从 api_call_log 聚合出 API 运行统计页（overview/trend/health-distribution/top-apis/error-codes/
 * top-keys/rate-limit-trend）与单 API 统计。健康分级对齐告警 PASS/WARNING/SEVERE 语义：
 * 按错误率 / P95 耗时 / 限流命中率 综合分级（任一命中即升级）。
 */
@Service
public class StatsQueryService {

    /** 健康分级阈值（对齐告警 PASS/WARNING/SEVERE，任一命中即升级） */
    private static final double SEVERE_ERROR_RATE = 0.05;
    private static final double WARNING_ERROR_RATE = 0.01;
    private static final double SEVERE_P95_MS = 1000;
    private static final double WARNING_P95_MS = 500;
    private static final double WARNING_RATE_LIMIT_RATIO = 0.05;

    private static final String LEVEL_PASS = "PASS";
    private static final String LEVEL_WARNING = "WARNING";
    private static final String LEVEL_SEVERE = "SEVERE";

    private final ApiCallLogMapper callLogMapper;
    private final DataApiMapper dataApiMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyBindingMapper bindingMapper;

    public StatsQueryService(ApiCallLogMapper callLogMapper, DataApiMapper dataApiMapper, ApiKeyMapper apiKeyMapper,
                             ApiKeyBindingMapper bindingMapper) {
        this.callLogMapper = callLogMapper;
        this.dataApiMapper = dataApiMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.bindingMapper = bindingMapper;
    }

    /** 全局 KPI：总调用量/成功率/P95/限流命中 */
    public StatsOverviewDTO overview(String range) {
        Range r = parseRange(range);
        OverviewAgg agg = callLogMapper.overviewSince(r.since());
        StatsOverviewDTO dto = new StatsOverviewDTO();
        long total = agg == null || agg.getTotalCalls() == null ? 0 : agg.getTotalCalls();
        long success = agg == null || agg.getSuccessCalls() == null ? 0 : agg.getSuccessCalls();
        long limited = agg == null || agg.getRateLimited() == null ? 0 : agg.getRateLimited();
        dto.setTotalCalls(total);
        dto.setSuccessRate(total == 0 ? 0 : (double) success / total);
        dto.setP95Ms(agg == null || agg.getP95() == null ? 0 : agg.getP95());
        dto.setRateLimitedCount(limited);
        dto.setRateLimitRatio(total == 0 ? 0 : (double) limited / total);
        return dto;
    }

    /** 全局调用量趋势（双线：调用量 + 失败数） */
    public List<TrendAgg> trend(String range) {
        Range r = parseRange(range);
        return callLogMapper.trendSince(r.since(), r.unit());
    }

    /** API 健康分布（健康/警告/严重 + 综合健康分） */
    public StatsHealthDistributionDTO healthDistribution(String range) {
        Range r = parseRange(range);
        List<CallStatAgg> aggs = callLogMapper.statByApiSince(r.since());
        Map<Long, DataApi> apiMap = apiMap(aggs.stream().map(CallStatAgg::getRefId).toList());

        List<StatsHealthItemDTO> items = new ArrayList<>();
        for (CallStatAgg agg : aggs) {
            DataApi api = apiMap.get(agg.getRefId());
            StatsHealthItemDTO item = new StatsHealthItemDTO();
            item.setApiId(agg.getRefId());
            item.setName(api == null ? "已删除的 API" : api.getName());
            item.setPath(api == null ? null : api.getPath());
            long total = agg.getTotalCalls() == null ? 0 : agg.getTotalCalls();
            long failed = agg.getFailedCalls() == null ? 0 : agg.getFailedCalls();
            long limited = agg.getRateLimited() == null ? 0 : agg.getRateLimited();
            double errorRate = total == 0 ? 0 : (double) failed / total;
            double rateLimitRatio = total == 0 ? 0 : (double) limited / total;
            double p95 = agg.getP95() == null ? 0 : agg.getP95();
            item.setTotalCalls(total);
            item.setErrorRate(round2(errorRate));
            item.setP95Ms(round2(p95));
            item.setRateLimitRatio(round2(rateLimitRatio));
            item.setLevel(grade(errorRate, p95, rateLimitRatio));
            items.add(item);
        }

        StatsHealthDistributionDTO dto = new StatsHealthDistributionDTO();
        int healthy = (int) items.stream().filter(i -> LEVEL_PASS.equals(i.getLevel())).count();
        int warning = (int) items.stream().filter(i -> LEVEL_WARNING.equals(i.getLevel())).count();
        int severe = (int) items.stream().filter(i -> LEVEL_SEVERE.equals(i.getLevel())).count();
        dto.setHealthyCount(healthy);
        dto.setWarningCount(warning);
        dto.setSevereCount(severe);
        dto.setOverallScore(overallScore(healthy, warning, severe));
        dto.setItems(items);
        return dto;
    }

    /** Top N API 调用排行（含名称/路径，软删显示「已删除」） */
    public List<StatsTopApiDTO> topApis(String range, int limit) {
        Range r = parseRange(range);
        List<RefCount> counts = callLogMapper.topApisSince(r.since(), limit);
        Map<Long, DataApi> apiMap = apiMap(counts.stream().map(RefCount::getRefId).toList());
        return counts.stream().map(c -> {
            DataApi api = apiMap.get(c.getRefId());
            StatsTopApiDTO dto = new StatsTopApiDTO();
            dto.setApiId(c.getRefId());
            dto.setName(api == null ? "已删除的 API" : api.getName());
            dto.setPath(api == null ? null : api.getPath());
            dto.setCalls(c.getCnt());
            return dto;
        }).toList();
    }

    /** 错误码分布（4xx/5xx TopN，含占比；429 条目附带限流命中最多的 API 名） */
    public List<StatsErrorCodeDTO> errorCodes(String range, int limit) {
        Range r = parseRange(range);
        List<StatusAgg> aggs = callLogMapper.errorCodesSince(r.since(), limit);
        long totalErrors = aggs.stream().mapToLong(a -> a.getCnt() == null ? 0 : a.getCnt()).sum();
        List<StatsErrorCodeDTO> dtos = aggs.stream().map(a -> {
            StatsErrorCodeDTO dto = new StatsErrorCodeDTO();
            dto.setStatusCode(a.getStatusCode());
            dto.setCount(a.getCnt());
            dto.setRatio(totalErrors == 0 ? 0 : round2((double) a.getCnt() / totalErrors));
            return dto;
        }).toList();
        // 429 命中集中的 API（原型：错误码提示「命中集中在 X」；API 软删显示「已删除的 API」）
        RefCount top429 = callLogMapper.top429ApiSince(r.since());
        if (top429 != null) {
            DataApi api = apiMap(List.of(top429.getRefId())).get(top429.getRefId());
            String name = api == null ? "已删除的 API" : api.getName();
            dtos.stream().filter(d -> d.getStatusCode() != null && d.getStatusCode() == 429)
                    .findFirst().ifPresent(d -> d.setTop429ApiName(name));
        }
        return dtos;
    }

    /** 调用方 Key 排行（Top N 有调用 + 近 7 天 0 调用的僵尸 Key 灰显） */
    public List<StatsTopKeyDTO> topKeys(String range, int limit) {
        Range r = parseRange(range);
        List<RefCount> counts = callLogMapper.topKeysSince(r.since(), limit);
        Map<Long, ApiKey> keyMap = keyMap(counts.stream().map(RefCount::getRefId).toList());

        List<StatsTopKeyDTO> items = counts.stream().map(c -> {
            ApiKey key = keyMap.get(c.getRefId());
            StatsTopKeyDTO dto = new StatsTopKeyDTO();
            dto.setKeyId(c.getRefId());
            dto.setName(key == null ? "已删除的 Key" : key.getName());
            dto.setCalls(c.getCnt());
            dto.setZombie(false);
            return dto;
        }).collect(Collectors.toCollection(ArrayList::new));

        // 僵尸 Key：启用且近 7 天 0 调用（固定 7 天口径，PRD 6.5.3）
        List<ApiKey> enabledKeys = apiKeyMapper.selectList(
                new QueryWrapper<ApiKey>().eq("status", ApiKey.STATUS_ENABLED));
        if (!enabledKeys.isEmpty()) {
            List<Long> allIds = enabledKeys.stream().map(ApiKey::getId).toList();
            Set<Long> active7d = new HashSet<>(callLogMapper.countCallsByKeyIdsSince(
                    allIds, LocalDateTime.now().minusDays(7)).stream().map(RefCount::getRefId).toList());
            List<StatsTopKeyDTO> zombies = enabledKeys.stream()
                    .filter(k -> !active7d.contains(k.getId()))
                    .map(k -> {
                        StatsTopKeyDTO dto = new StatsTopKeyDTO();
                        dto.setKeyId(k.getId());
                        dto.setName(k.getName());
                        dto.setCalls(0L);
                        dto.setZombie(true);
                        return dto;
                    }).toList();
            items.addAll(zombies);
        }
        // 绑定 API 数批量回填（有调用 TopN + 僵尸全量，一次查询避免 N+1）
        List<Long> allKeyIds = items.stream().map(StatsTopKeyDTO::getKeyId).toList();
        Map<Long, Long> boundApiCounts = allKeyIds.isEmpty() ? Map.of()
                : bindingMapper.countApisByKeyIds(allKeyIds).stream()
                .collect(Collectors.toMap(RefCount::getRefId, RefCount::getCnt, (a, b) -> a));
        items.forEach(i -> i.setBoundApiCount(boundApiCounts.getOrDefault(i.getKeyId(), 0L)));
        return items;
    }

    /** 限流命中趋势（429 按时间桶） */
    public List<TrendAgg> rateLimitTrend(String range) {
        Range r = parseRange(range);
        return callLogMapper.rateLimitTrendSince(r.since(), r.unit());
    }

    /** 单 API 调用统计（调用量/成功率/平均/P95/今日 + 趋势 + 最近明细） */
    public ApiStatsDTO apiStats(Long apiId, String range) {
        Range r = parseRange(range);
        OverviewAgg agg = callLogMapper.overviewByApiSince(apiId, r.since());
        Double avg = callLogMapper.avgDurationByApiSince(apiId, r.since());
        Long today = callLogMapper.countCallsByApiSince(apiId, LocalDate.now().atStartOfDay());

        long total = agg == null || agg.getTotalCalls() == null ? 0 : agg.getTotalCalls();
        long success = agg == null || agg.getSuccessCalls() == null ? 0 : agg.getSuccessCalls();

        ApiStatsDTO dto = new ApiStatsDTO();
        dto.setTotalCalls(total);
        dto.setSuccessRate(total == 0 ? 0 : round2((double) success / total));
        dto.setAvgMs(avg == null ? 0 : round2(avg));
        dto.setP95Ms(agg == null || agg.getP95() == null ? 0 : round2(agg.getP95()));
        dto.setTodayCalls(today == null ? 0 : today);
        dto.setTrend(callLogMapper.trendByApiSince(apiId, r.since(), r.unit()));
        dto.setRecentLogs(recentLogs(apiId));
        dto.setHourly(callLogMapper.hourlyByApiSince(apiId, LocalDate.now().atStartOfDay()));
        dto.setTopKeys(topKeysByApi(apiId, r.since()));
        dto.setStatusBreakdown(callLogMapper.statusBreakdownByApiSince(apiId, r.since()));
        return dto;
    }

    // ---------- 内部方法 ----------

    /** 单 API 调用方 Key 排行（Top 5，key 名反查；zombie 恒 false） */
    private List<StatsTopKeyDTO> topKeysByApi(Long apiId, LocalDateTime since) {
        List<RefCount> counts = callLogMapper.topKeysByApiSince(apiId, since, 5);
        Map<Long, ApiKey> keyMap = keyMap(counts.stream().map(RefCount::getRefId).toList());
        return counts.stream().map(c -> {
            ApiKey key = keyMap.get(c.getRefId());
            StatsTopKeyDTO dto = new StatsTopKeyDTO();
            dto.setKeyId(c.getRefId());
            dto.setName(key == null ? "已删除的 Key" : key.getName());
            dto.setCalls(c.getCnt());
            dto.setZombie(false);
            return dto;
        }).toList();
    }


    /** 最近 5 条调用明细（PRD 6.5.2「最新 5 条 · 异常高亮」） */
    private List<ApiCallLogItemDTO> recentLogs(Long apiId) {
        Page<ApiCallLog> page = callLogMapper.selectPage(new Page<>(1, 5),
                new QueryWrapper<ApiCallLog>().eq("api_id", apiId).orderByDesc("created_at"));
        List<Long> keyIds = page.getRecords().stream().map(ApiCallLog::getKeyId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, ApiKey> keyMap = keyMap(keyIds);
        return page.getRecords().stream().map(log -> {
            ApiCallLogItemDTO dto = new ApiCallLogItemDTO();
            ApiKey key = log.getKeyId() == null ? null : keyMap.get(log.getKeyId());
            dto.setKeyName(key == null ? null : key.getName());
            dto.setStatusCode(log.getStatusCode());
            dto.setDurationMs(log.getDurationMs());
            dto.setCreatedAt(log.getCreatedAt());
            return dto;
        }).toList();
    }

    /** 健康分级：任一命中即升级（SEVERE 优先于 WARNING） */
    private String grade(double errorRate, double p95, double rateLimitRatio) {
        if (errorRate >= SEVERE_ERROR_RATE || p95 >= SEVERE_P95_MS) {
            return LEVEL_SEVERE;
        }
        if (errorRate >= WARNING_ERROR_RATE || p95 >= WARNING_P95_MS || rateLimitRatio >= WARNING_RATE_LIMIT_RATIO) {
            return LEVEL_WARNING;
        }
        return LEVEL_PASS;
    }

    /** 综合健康分：PASS=100 / WARNING=60 / SEVERE=20 平均，四舍五入；无 API 记 100 */
    private int overallScore(int healthy, int warning, int severe) {
        int total = healthy + warning + severe;
        if (total == 0) {
            return 100;
        }
        return (int) Math.round((healthy * 100.0 + warning * 60.0 + severe * 20.0) / total);
    }

    private Map<Long, DataApi> apiMap(List<Long> apiIds) {
        if (apiIds == null || apiIds.isEmpty()) {
            return Map.of();
        }
        return dataApiMapper.selectBatchIds(apiIds).stream()
                .collect(Collectors.toMap(DataApi::getId, Function.identity()));
    }

    private Map<Long, ApiKey> keyMap(List<Long> keyIds) {
        if (keyIds == null || keyIds.isEmpty()) {
            return Map.of();
        }
        return apiKeyMapper.selectBatchIds(keyIds).stream()
                .collect(Collectors.toMap(ApiKey::getId, Function.identity()));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 时间范围解析：24h（按小时）/ 7d、30d（按天），默认 24h */
    private Range parseRange(String range) {
        LocalDateTime now = LocalDateTime.now();
        return switch (range == null ? "" : range.trim()) {
            case "7d" -> new Range(now.minusDays(7), "day");
            case "30d" -> new Range(now.minusDays(30), "day");
            default -> new Range(now.minusHours(24), "hour");
        };
    }

    private record Range(LocalDateTime since, String unit) {
    }
}
