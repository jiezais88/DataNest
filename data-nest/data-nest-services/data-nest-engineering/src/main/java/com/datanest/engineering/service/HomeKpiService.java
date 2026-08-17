package com.datanest.engineering.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.engineering.dto.DagExecutionStatsDTO;
import com.datanest.engineering.dto.HomeKpiDTO;
import com.datanest.engineering.dto.SyncJobHistoryStatsDTO;
import com.datanest.engineering.entity.Dag;
import com.datanest.engineering.entity.DagExecution;
import com.datanest.engineering.entity.SyncJobHistory;
import com.datanest.engineering.mapper.DagExecutionMapper;
import com.datanest.engineering.mapper.DagMapper;
import com.datanest.engineering.mapper.SyncJobHistoryMapper;
import com.datanest.engineering.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sprint 11 F5 首页 KPI 聚合（工程域：DAG + 同步任务）。
 * <p>
 * 数据口径（与列表页统计卡一致）：
 * - 今日/昨日：按 start_time 落在自然日聚合 dag_execution + sync_job_history
 * - 成功率：近 7 天 SUCCESS / (SUCCESS+FAILED)，环比再前 7 天
 * - 运行中：status=RUNNING 的 DAG 执行 + 同步执行（CDC 由 realtime 侧补齐，前端合并）
 * - 失败待处理：近 7 天 FAILED 且该任务（同 DAG / 同同步任务）近 7 天无后续 SUCCESS（近似「已处理」）；v4.1 起按任务去重计数（同一任务多次失败只计 1 项）
 * - 14 日趋势：按天 GROUP BY，service 层补零 + 计算异常日（失败率 > 10%）
 * 各 KPI 允许 5 分钟级延迟（PRD HP-1）。
 */
@Service
public class HomeKpiService {

    private static final Logger logger = LoggerFactory.getLogger(HomeKpiService.class);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final DagExecutionMapper dagExecutionMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final DagMapper dagMapper;
    private final SyncJobMapper syncJobMapper;

    public HomeKpiService(DagExecutionMapper dagExecutionMapper,
                          SyncJobHistoryMapper syncJobHistoryMapper,
                          DagMapper dagMapper,
                          SyncJobMapper syncJobMapper) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.dagMapper = dagMapper;
        this.syncJobMapper = syncJobMapper;
    }

    /**
     * 工程域首页 KPI 聚合（DAG + 同步）。
     */
    public HomeKpiDTO build() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();
        LocalDateTime yesterdayStart = today.minusDays(1).atStartOfDay();
        LocalDateTime yesterdayEnd = todayStart;
        // 近 7 自然日（含今天）：[D-6 00:00, D+1 00:00)，环比再前 7 天 [D-13, D-6)，与趋势图/成功率口径一致
        LocalDateTime sevenDaysAgo = todayStart.minusDays(6);
        LocalDateTime fourteenDaysAgo = todayStart.minusDays(13);

        HomeKpiDTO dto = new HomeKpiDTO();

        // ---- KPI 1: 今日运行 + 今日状态分布（v4.1 分布条：成功/失败/运行中/等待） ----
        long todayDag = dagExecutionMapper.selectCount(new QueryWrapper<DagExecution>()
                .ge("start_time", todayStart).lt("start_time", todayEnd));
        long todaySync = syncJobHistoryMapper.selectCount(new QueryWrapper<SyncJobHistory>()
                .ge("start_time", todayStart).lt("start_time", todayEnd));
        long yestDag = dagExecutionMapper.selectCount(new QueryWrapper<DagExecution>()
                .ge("start_time", yesterdayStart).lt("start_time", yesterdayEnd));
        long yestSync = syncJobHistoryMapper.selectCount(new QueryWrapper<SyncJobHistory>()
                .ge("start_time", yesterdayStart).lt("start_time", yesterdayEnd));
        long todayTotal = todayDag + todaySync;
        long yestTotal = yestDag + yestSync;
        dto.setTodayTotal(todayTotal);
        dto.setYesterdayTotal(yestTotal);
        dto.setTodayDelta(todayTotal - yestTotal);
        DagExecutionStatsDTO dagStatsToday = dagExecutionMapper.selectStats(todayStart, todayEnd);
        SyncJobHistoryStatsDTO syncStatsToday = syncJobHistoryMapper.selectStats(todayStart, todayEnd);
        dto.setTodaySuccess(sum(dagStatsToday.getSuccess(), syncStatsToday.getSuccess()));
        dto.setTodayFailed(sum(dagStatsToday.getFailed(), syncStatsToday.getFailed()));
        dto.setWaiting(dagExecutionMapper.selectCount(new QueryWrapper<DagExecution>().eq("status", "WAITING")));

        // ---- KPI 2: 成功率（近 7 天 + 环比再前 7 天） ----
        DagExecutionStatsDTO dagStats7 = dagExecutionMapper.selectStats(sevenDaysAgo, todayEnd);
        SyncJobHistoryStatsDTO syncStats7 = syncJobHistoryMapper.selectStats(sevenDaysAgo, todayEnd);
        dto.setSuccessRate7d(computeRate(
                sum(dagStats7.getSuccess(), syncStats7.getSuccess()),
                sum(dagStats7.getFailed(), syncStats7.getFailed())));

        DagExecutionStatsDTO dagStatsPrev = dagExecutionMapper.selectStats(fourteenDaysAgo, sevenDaysAgo);
        SyncJobHistoryStatsDTO syncStatsPrev = syncJobHistoryMapper.selectStats(fourteenDaysAgo, sevenDaysAgo);
        Double prevRate = computeRate(
                sum(dagStatsPrev.getSuccess(), syncStatsPrev.getSuccess()),
                sum(dagStatsPrev.getFailed(), syncStatsPrev.getFailed()));
        // 近 7 天无样本或环比无样本时，delta 也置 null（避免误导性负数）
        if (dto.getSuccessRate7d() != null && prevRate != null) {
            dto.setSuccessRateDelta(round1(dto.getSuccessRate7d() - prevRate));
        }

        // ---- KPI 3: 运行中 ----
        long runningDag = dagExecutionMapper.selectCount(new QueryWrapper<DagExecution>().eq("status", "RUNNING"));
        long runningSync = syncJobHistoryMapper.selectCount(new QueryWrapper<SyncJobHistory>().eq("status", "RUNNING"));
        dto.setRunning(runningDag + runningSync);

        // ---- KPI 4: 失败待处理（近 7 天 FAILED 且无后续 SUCCESS，按任务去重计数） ----
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        List<DagExecution> failedDags = dagExecutionMapper.selectList(new QueryWrapper<DagExecution>()
                .eq("status", "FAILED").ge("start_time", sevenDaysAgo).orderByDesc("start_time"));
        List<SyncJobHistory> failedSyncs = syncJobHistoryMapper.selectList(new QueryWrapper<SyncJobHistory>()
                .eq("status", "FAILED").ge("start_time", sevenDaysAgo).orderByDesc("start_time"));

        // 批量恢复判定：一次查询近 7 天各任务最近一次 SUCCESS 时间（避免循环内 selectCount 的 N+1）
        Map<Long, LocalDateTime> lastSuccessDag = lastSuccessTimeByDag(failedDags, sevenDaysAgo);
        Map<Long, LocalDateTime> lastSuccessSync = lastSuccessTimeBySyncJob(failedSyncs, sevenDaysAgo);

        long failedLast1h = 0;
        long pendingFailed = 0;
        List<HomeKpiDTO.FailedItem> failedItems = new ArrayList<>();
        Set<Long> countedDagIds = new HashSet<>();
        for (DagExecution e : failedDags) {
            // 恢复判定：该次失败之后已有 SUCCESS 才算已恢复（先后必须比较，窗口内存在 SUCCESS 不算数）
            if (isRecovered(lastSuccessDag.get(e.getDagId()), e.getStartTime())) {
                continue;
            }
            if (e.getStartTime() != null && e.getStartTime().isAfter(oneHourAgo)) {
                failedLast1h++;
            }
            // v4.1：同一任务多次失败只计 1 项（与首页异常队列「一行一任务」语义对齐）
            if (!countedDagIds.add(e.getDagId())) {
                continue;
            }
            pendingFailed++;
            if (failedItems.size() < 3) {
                HomeKpiDTO.FailedItem item = new HomeKpiDTO.FailedItem();
                item.setType("dag");
                item.setName(dagNameOf(e.getDagId()));
                item.setExecutionId(String.valueOf(e.getId()));
                item.setRefId(e.getDagId() == null ? null : String.valueOf(e.getDagId()));
                item.setFailedAt(formatTime(e.getEndTime() != null ? e.getEndTime() : e.getStartTime()));
                item.setReason(brief(e.getErrorMessage()));
                failedItems.add(item);
            }
        }
        Set<Long> countedSyncJobIds = new HashSet<>();
        for (SyncJobHistory h : failedSyncs) {
            if (isRecovered(lastSuccessSync.get(h.getSyncJobId()), h.getStartTime())) {
                continue;
            }
            if (h.getStartTime() != null && h.getStartTime().isAfter(oneHourAgo)) {
                failedLast1h++;
            }
            if (!countedSyncJobIds.add(h.getSyncJobId())) {
                continue;
            }
            pendingFailed++;
            if (failedItems.size() < 3) {
                HomeKpiDTO.FailedItem item = new HomeKpiDTO.FailedItem();
                item.setType("sync");
                item.setName(syncJobNameOf(h.getSyncJobId()));
                item.setExecutionId(String.valueOf(h.getId()));
                item.setRefId(h.getSyncJobId() == null ? null : String.valueOf(h.getSyncJobId()));
                item.setFailedAt(formatTime(h.getEndTime() != null ? h.getEndTime() : h.getStartTime()));
                item.setReason(brief(h.getErrorMessage()));
                failedItems.add(item);
            }
        }
        dto.setPendingFailed(pendingFailed);
        dto.setFailedLast1h(failedLast1h);
        dto.setFailedItems(failedItems);

        // ---- 14 日趋势（v4.1：7 → 14 天窗口） ----
        dto.setTrend(buildTrend(fourteenDaysAgo, todayEnd));

        return dto;
    }

    // ==================== 内部 ====================

    private List<HomeKpiDTO.TrendPoint> buildTrend(LocalDateTime since, LocalDateTime until) {
        // 近 14 个自然日（含今天）：今天往前数 13 天，共 14 天
        Map<String, HomeKpiDTO.TrendPoint> byDay = new LinkedHashMap<>();
        LocalDate cursor = LocalDate.now().minusDays(13);
        LocalDate end = LocalDate.now();
        while (!cursor.isAfter(end)) {
            HomeKpiDTO.TrendPoint p = new HomeKpiDTO.TrendPoint();
            p.setDay(cursor.format(DAY_FMT));
            p.setTotal(0L);
            p.setSuccess(0L);
            p.setFailed(0L);
            p.setAbnormal(false);
            byDay.put(p.getDay(), p);
            cursor = cursor.plusDays(1);
        }
        // DAG + 同步按天聚合合并
        Map<String, HomeKpiDTO.TrendPoint> agg = new HashMap<>();
        for (HomeKpiDTO.TrendPoint p : merge(byDay.keySet())) {
            agg.put(p.getDay(), p);
        }
        for (String day : byDay.keySet()) {
            HomeKpiDTO.TrendPoint target = byDay.get(day);
            HomeKpiDTO.TrendPoint a = agg.get(day);
            if (a != null) {
                target.setTotal(a.getTotal());
                target.setSuccess(a.getSuccess());
                target.setFailed(a.getFailed());
            }
            // 异常日：失败率 > 10% 且失败数 > 0
            long denom = target.getTotal() == null ? 0 : target.getTotal();
            if (denom > 0 && (target.getFailed() * 100.0 / denom) > 10.0) {
                target.setAbnormal(true);
            }
        }
        return new ArrayList<>(byDay.values());
    }

    /** 合并 dag_execution 与 sync_job_history 的按天聚合 */
    private List<HomeKpiDTO.TrendPoint> merge(java.util.Set<String> days) {
        List<HomeKpiDTO.TrendPoint> result = new ArrayList<>();
        Map<String, HomeKpiDTO.TrendPoint> index = new HashMap<>();
        for (String day : days) {
            HomeKpiDTO.TrendPoint p = new HomeKpiDTO.TrendPoint();
            p.setDay(day);
            p.setTotal(0L);
            p.setSuccess(0L);
            p.setFailed(0L);
            index.put(day, p);
            result.add(p);
        }
        for (DagExecutionMapper.DagDailyStat s : dagExecutionMapper.selectDailyStats(LocalDateTime.now().minusDays(13).toLocalDate().atStartOfDay())) {
            HomeKpiDTO.TrendPoint p = index.get(s.getDay());
            if (p != null) {
                p.setTotal(p.getTotal() + s.getTotal());
                p.setSuccess(p.getSuccess() + s.getSuccess());
                p.setFailed(p.getFailed() + s.getFailed());
            }
        }
        for (DagExecutionMapper.DagDailyStat s : syncJobHistoryMapper.selectDailyStats(LocalDateTime.now().minusDays(13).toLocalDate().atStartOfDay())) {
            HomeKpiDTO.TrendPoint p = index.get(s.getDay());
            if (p != null) {
                p.setTotal(p.getTotal() + s.getTotal());
                p.setSuccess(p.getSuccess() + s.getSuccess());
                p.setFailed(p.getFailed() + s.getFailed());
            }
        }
        return result;
    }

    /** 近 7 天各 DAG 最近一次 SUCCESS 时间（批量一次查询） */
    private Map<Long, LocalDateTime> lastSuccessTimeByDag(List<DagExecution> failedDags, LocalDateTime since) {
        List<Long> dagIds = failedDags.stream().map(DagExecution::getDagId).filter(Objects::nonNull).distinct().toList();
        if (dagIds.isEmpty()) return Map.of();
        Map<Long, LocalDateTime> result = new HashMap<>();
        for (Map<String, Object> row : dagExecutionMapper.lastSuccessTimeByDagIdsSince(dagIds, since)) {
            Object ts = row.get("last_success");
            if (ts != null) {
                result.put(((Number) row.get("dag_id")).longValue(), ((java.sql.Timestamp) ts).toLocalDateTime());
            }
        }
        return result;
    }

    /** 近 7 天各同步任务最近一次 SUCCESS 时间（批量一次查询） */
    private Map<Long, LocalDateTime> lastSuccessTimeBySyncJob(List<SyncJobHistory> failedSyncs, LocalDateTime since) {
        List<Long> jobIds = failedSyncs.stream().map(SyncJobHistory::getSyncJobId).filter(Objects::nonNull).distinct().toList();
        if (jobIds.isEmpty()) return Map.of();
        Map<Long, LocalDateTime> result = new HashMap<>();
        for (Map<String, Object> row : syncJobHistoryMapper.lastSuccessTimeByJobIdsSince(jobIds, since)) {
            Object ts = row.get("last_success");
            if (ts != null) {
                result.put(((Number) row.get("sync_job_id")).longValue(), ((java.sql.Timestamp) ts).toLocalDateTime());
            }
        }
        return result;
    }

    /** 失败之后已有 SUCCESS = 已恢复（null 安全；lastSuccess 必须晚于 failedAt） */
    private boolean isRecovered(LocalDateTime lastSuccess, LocalDateTime failedAt) {
        return lastSuccess != null && failedAt != null && lastSuccess.isAfter(failedAt);
    }

    /** 时间统一格式化 yyyy-MM-dd HH:mm:ss（禁止 LocalDateTime.toString() 的 ISO 带 T 格式） */
    private String formatTime(LocalDateTime t) {
        return t == null ? null : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String dagNameOf(Long dagId) {
        if (dagId == null) return String.valueOf(dagId);
        try {
            Dag dag = dagMapper.selectById(dagId);
            return dag == null ? String.valueOf(dagId) : dag.getName();
        } catch (Exception e) {
            return String.valueOf(dagId);
        }
    }

    private String syncJobNameOf(Long syncJobId) {
        if (syncJobId == null) return String.valueOf(syncJobId);
        try {
            com.datanest.engineering.entity.SyncJob job = syncJobMapper.selectById(syncJobId);
            return job == null ? String.valueOf(syncJobId) : job.getName();
        } catch (Exception e) {
            return String.valueOf(syncJobId);
        }
    }

    private String brief(String msg) {
        if (msg == null || msg.isBlank()) return "执行失败";
        String m = msg.trim();
        return m.length() > 60 ? m.substring(0, 60) + "..." : m;
    }

    private long sum(Long a, Long b) {
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    /** 成功率 0~100，无样本返回 null */
    private Double computeRate(long success, long failed) {
        long total = success + failed;
        if (total == 0) return null;
        return round1(success * 100.0 / total);
    }

    private Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
