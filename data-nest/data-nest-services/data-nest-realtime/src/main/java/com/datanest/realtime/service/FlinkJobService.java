package com.datanest.realtime.service;

import org.apache.flink.cdc.cli.parser.YamlPipelineDefinitionParser;
import org.apache.flink.cdc.composer.PipelineExecution;
import org.apache.flink.cdc.composer.definition.PipelineDef;
import org.apache.flink.cdc.composer.flink.FlinkPipelineComposer;
import org.apache.flink.core.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flink 作业服务：CDC YAML 管道提交 / cancel-with-savepoint 停止 / 状态与指标查询。
 * <p>
 * 提交链路照抄 Sprint 8 M0 验证范本 {@code tmp/m0-cdc-verify/VerifyMain.java}：
 * YamlPipelineDefinitionParser 解析 YAML → FlinkPipelineComposer.ofRemoteCluster 组装
 * → execution.execute() 经 REST 提交到独立 Flink 2.2.1 Session 集群。
 * 停止/状态/指标走 Flink REST API（RestClient）。
 */
@Service
public class FlinkJobService {

    private static final Logger logger = LoggerFactory.getLogger(FlinkJobService.class);

    /** savepoint 落盘目录（per-job 覆盖，集群无需改配置） */
    private static final String SAVEPOINT_DIR = "s3a://datalake/savepoints";

    /** savepoint 触发轮询间隔（毫秒） */
    private static final long SAVEPOINT_POLL_INTERVAL_MS = 2000;
    /** savepoint 触发最长等待（毫秒） */
    private static final long SAVEPOINT_POLL_TIMEOUT_MS = 60000;

    private final RestClient restClient;

    /** Flink JobManager REST 地址（容器内网，如 http://middleware-flink-jobmanager:8081） */
    private final String jobmanagerUrl;
    private final String jobmanagerHost;
    private final String jobmanagerPort;

    /** 作业并行度（提交配置用） */
    @Value("${datanest.realtime.flink.parallelism:1}")
    private Integer parallelism;

    /** checkpoint 间隔（毫秒） */
    @Value("${datanest.realtime.flink.checkpoint-interval-ms:30000}")
    private Long checkpointIntervalMs;

    public FlinkJobService(@Value("${datanest.realtime.flink.jobmanager-url}") String jobmanagerUrl) {
        this.jobmanagerUrl = jobmanagerUrl.endsWith("/") ? jobmanagerUrl.substring(0, jobmanagerUrl.length() - 1) : jobmanagerUrl;
        URI uri = URI.create(this.jobmanagerUrl);
        this.jobmanagerHost = uri.getHost();
        this.jobmanagerPort = String.valueOf(uri.getPort());
        // 必须显式超时：默认无超时，JM 半挂（TCP 通但不响应）会卡死监控调度线程与用户请求线程
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 集群概览（/overview）：Task Slot 总数/空闲数等，供向导并行度容量动态提示。
     * 复用带超时的 restClient，JM 半挂最多阻塞 15s。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getClusterOverview() {
        return restClient.get()
                .uri(jobmanagerUrl + "/overview")
                .retrieve()
                .body(Map.class);
    }

    /**
     * 提交 CDC YAML 管道到 Flink Session 集群。
     *
     * @param yaml                         CDC YAML 管道定义
     * @param savepointPath                非空时从该 savepoint 恢复（不丢不重）
     * @param checkpointIntervalMsOverride 非空时覆盖 Nacos 默认 checkpoint 间隔（configJson 高级配置，
     *                                     毫秒）。composer 不消费 YAML pipeline 段的任意键，只能走提交侧配置
     * @return Flink 作业 ID
     * @throws Exception YAML 解析/组装/提交失败（调用方统一 catch 落 ERROR）
     */
    public String submit(String yaml, String savepointPath, Long checkpointIntervalMsOverride) throws Exception {
        // Flink 远程提交配置（Session 集群，官方配置 key：execution.target / remote.address / remote.port）
        org.apache.flink.configuration.Configuration flinkConfig = new org.apache.flink.configuration.Configuration();
        flinkConfig.setString("execution.target", "remote");
        flinkConfig.setString("remote.address", jobmanagerHost);
        flinkConfig.setString("remote.port", jobmanagerPort);
        // RestClusterClient 连接集群需要 rest.address/rest.port
        flinkConfig.setString("rest.address", jobmanagerHost);
        flinkConfig.setString("rest.port", jobmanagerPort);
        // savepoint 目录 per-job 覆盖 + checkpoint 间隔（configJson 高级配置优先于 Nacos 默认）
        flinkConfig.setString("state.savepoints.dir", SAVEPOINT_DIR);
        flinkConfig.setString("execution.checkpointing.interval",
                String.valueOf(checkpointIntervalMsOverride != null ? checkpointIntervalMsOverride : checkpointIntervalMs));
        // 有 savepoint 时优先恢复（配置变更后 savepoint_path 已清空，不会误用旧状态）。
        // Flink 2.x 已移除 SavepointRestoreSettings 类，恢复配置走 execution.savepoint.* 配置键
        if (savepointPath != null && !savepointPath.isBlank()) {
            flinkConfig.setString("execution.savepoint.path", savepointPath);
            flinkConfig.setString("execution.savepoint.ignore-unclaimed-state", "false");
            logger.info("CDC 作业将从 savepoint 恢复: {}", savepointPath);
        }

        // additionalJars：空列表。CDC connector + MySQL 驱动已预置到集群 /opt/flink/lib，
        // 提交端仅保留 classpath 用于 YAML 解析/组装
        List<Path> additionalJars = List.of();

        PipelineDef pipelineDef = new YamlPipelineDefinitionParser()
                .parse(yaml, new org.apache.flink.cdc.common.configuration.Configuration());
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofRemoteCluster(flinkConfig, additionalJars);
        PipelineExecution execution = composer.compose(pipelineDef);
        PipelineExecution.ExecutionInfo info = execution.execute();
        logger.info("CDC 作业提交成功: {}", info);
        return info.getId();
    }

    /**
     * cancel-with-savepoint 停止作业：触发停止 → 轮询取回 savepoint 路径。
     *
     * @param jobId Flink 作业 ID
     * @return savepoint 路径（s3a://...）
     */
    @SuppressWarnings("unchecked")
    public String stopWithSavepoint(String jobId) {
        // 触发 stop-with-savepoint（drain=false：不等待 source 耗尽，立即做 savepoint）。
        // Flink 2.2 的 StopWithSavepointRequestBody 已无 formatType 字段，
        // 且集群未配默认 savepoint 目录时 targetDirectory 必填
        Map<String, Object> triggerResponse = restClient.post()
                .uri(jobmanagerUrl + "/jobs/{jobId}/stop", jobId)
                .body(Map.of("drain", false, "targetDirectory", SAVEPOINT_DIR))
                .retrieve()
                .body(Map.class);
        Object requestId = triggerResponse == null ? null : triggerResponse.get("request-id");
        if (requestId == null) {
            throw new IllegalStateException("Flink stop 触发响应缺少 request-id: " + triggerResponse);
        }
        return pollSavepointResult(jobId, String.valueOf(requestId));
    }

    /**
     * 手动触发 savepoint（POST /jobs/{id}/savepoints），轮询取回路径。
     * <p>
     * M0 实测（Flink 2.2.1）：手动触发 body 必须是 kebab-case {@code target-directory / cancel-job}，
     * camelCase 会被静默忽略报「Property [target-directory] must be provided」——
     * 与 stop-with-savepoint 的 camelCase（drain/targetDirectory）命名风格不同，勿混。
     */
    @SuppressWarnings("unchecked")
    public String triggerSavepoint(String jobId) {
        Map<String, Object> triggerResponse = restClient.post()
                .uri(jobmanagerUrl + "/jobs/{jobId}/savepoints", jobId)
                .body(Map.of("target-directory", SAVEPOINT_DIR, "cancel-job", false))
                .retrieve()
                .body(Map.class);
        Object requestId = triggerResponse == null ? null : triggerResponse.get("request-id");
        if (requestId == null) {
            throw new IllegalStateException("Flink savepoint 触发响应缺少 request-id: " + triggerResponse);
        }
        return pollSavepointResult(jobId, String.valueOf(requestId));
    }

    /**
     * 轮询 savepoint 触发结果（每 2s，最多 60s），COMPLETED 返回路径，失败/超时抛异常。
     * 抽自 stopWithSavepoint，手动触发 savepoint 复用同一逻辑。
     */
    @SuppressWarnings("unchecked")
    private String pollSavepointResult(String jobId, String requestId) {
        long deadline = System.currentTimeMillis() + SAVEPOINT_POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = restClient.get()
                    .uri(jobmanagerUrl + "/jobs/{jobId}/savepoints/{requestId}", jobId, requestId)
                    .retrieve()
                    .body(Map.class);
            Map<String, Object> status = info == null ? null : (Map<String, Object>) info.get("status");
            String statusId = status == null ? null : String.valueOf(status.get("id"));
            if ("COMPLETED".equals(statusId)) {
                Map<String, Object> operation = (Map<String, Object>) info.get("operation");
                Object location = operation == null ? null : operation.get("location");
                if (location == null) {
                    Object failureCause = operation == null ? null : operation.get("failure-cause");
                    throw new IllegalStateException("savepoint 触发失败: " + failureCause);
                }
                return String.valueOf(location);
            }
            try {
                Thread.sleep(SAVEPOINT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("savepoint 轮询被中断", e);
            }
        }
        throw new IllegalStateException("savepoint 触发超时（" + SAVEPOINT_POLL_TIMEOUT_MS / 1000 + "s 内未完成）");
    }

    /**
     * 查询作业 checkpoint 状态（/jobs/{id}/checkpoints），返回原始结构
     * （counts / summary / latest / history），由调用方裁剪为「检查点」页签三卡 + 最近 20 条。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCheckpoints(String jobId) {
        Map<String, Object> checkpoints = restClient.get()
                .uri(jobmanagerUrl + "/jobs/{jobId}/checkpoints", jobId)
                .retrieve()
                .body(Map.class);
        if (checkpoints == null) {
            throw new IllegalStateException("Flink checkpoints 响应为空: jobId=" + jobId);
        }
        return checkpoints;
    }

    /**
     * 取作业概览（/jobs/{id}，内嵌 state 与 vertices）。监控轮询一次调用同时提取状态与指标，
     * 避免 state/metrics 分两次请求。REST 异常（作业不存在/集群不可达）直接抛出，由调用方按「作业不可达」处理。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getJobOverview(String jobId) {
        Map<String, Object> job = restClient.get()
                .uri(jobmanagerUrl + "/jobs/{jobId}", jobId)
                .retrieve()
                .body(Map.class);
        if (job == null) {
            throw new IllegalStateException("Flink 作业概览响应为空: jobId=" + jobId);
        }
        return job;
    }

    /** 从作业概览提取状态（RUNNING/FAILED/CANCELED/FINISHED/SUSPENDED/...）。 */
    public String extractState(Map<String, Object> jobOverview) {
        Object state = jobOverview.get("state");
        if (state == null) {
            throw new IllegalStateException("Flink 作业状态响应缺少 state: " + jobOverview);
        }
        return String.valueOf(state);
    }

    /**
     * 查询作业最近异常根因（用于作业 FAILED 时回写 last_error）。
     * <p>
     * Flink 2.x 的 {@code GET /jobs/{jobId}/exceptions} 返回结构：
     * <pre>{@code
     * {
     *   "exceptionHistory": { "entries": [ { "exceptionName": ..., "stacktrace": ..., "timestamp": ... }, ... ] }
     * }
     * }</pre>
     * 顶层不再有 {@code root-exception}（旧版本字段，已废弃为空）。因此从 {@code exceptionHistory.entries[0].stacktrace}
     * 提取，兼容兜底 {@code root-exception} 与 {@code entries[0].exceptionName}。
     * <p>
     * 查询失败返回 null（不影响状态回写主流程）。
     */
    @SuppressWarnings("unchecked")
    public String getJobRootException(String jobId) {
        try {
            Map<String, Object> exceptions = restClient.get()
                    .uri(jobmanagerUrl + "/jobs/{jobId}/exceptions", jobId)
                    .retrieve()
                    .body(Map.class);
            if (exceptions == null) {
                return null;
            }
            // 1) 兼容旧结构：顶层 root-exception
            Object rootException = exceptions.get("root-exception");
            if (rootException != null && !String.valueOf(rootException).isBlank()) {
                return String.valueOf(rootException);
            }
            // 2) Flink 2.x：exceptionHistory.entries[0].stacktrace（完整堆栈）
            Map<String, Object> history = (Map<String, Object>) exceptions.get("exceptionHistory");
            if (history != null) {
                Object entriesObj = history.get("entries");
                if (entriesObj instanceof List<?> entries && !entries.isEmpty()) {
                    Map<String, Object> first = (Map<String, Object>) entries.get(0);
                    Object stacktrace = first.get("stacktrace");
                    if (stacktrace != null && !String.valueOf(stacktrace).isBlank()) {
                        return String.valueOf(stacktrace);
                    }
                    Object exceptionName = first.get("exceptionName");
                    if (exceptionName != null && !String.valueOf(exceptionName).isBlank()) {
                        return String.valueOf(exceptionName);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            logger.warn("查询 Flink 作业异常信息失败: jobId={}, error={}", jobId, e.getMessage());
            return null;
        }
    }

    /**
     * 从作业概览提取指标：{lagSeconds, totalChanges}。
     * <p>
     * vertex 列表取 /jobs/{id} 内嵌字段（Flink 2.x 无 /jobs/{id}/vertices 子资源）；
     * 指标 id 带子任务前缀与算子作用域名（如 {@code 0.numRecordsOut}、
     * {@code 0.Source__MySQL_Source.currentEmitEventTimeLag}），故先列可用指标再按后缀匹配聚合：
     * totalChanges = sink vertex（name 含 Sink）全部子任务 numRecordsOut 之和，**任一指标查询失败返回 -1**
     * （调用方 -1 时跳过回写，避免把累计变更误清 0）；
     * lagSeconds = source vertex（name 含 Source）currentEmitEventTimeLag 最大值（毫秒→秒），查不到返回 -1。
     * Sink/Source 用两个独立 if 判断：source/sink 被 chain 成单 vertex 时名称同时含两者，互斥分支会丢 lag。
     */
    @SuppressWarnings("unchecked")
    public long[] extractMetrics(String jobId, Map<String, Object> jobOverview) {
        List<Map<String, Object>> vertices =
                (List<Map<String, Object>>) jobOverview.get("vertices");

        long lagSeconds = -1;
        long totalChanges = 0;
        boolean changesUnavailable = false;
        if (vertices != null) {
            for (Map<String, Object> vertex : vertices) {
                String vertexId = String.valueOf(vertex.get("id"));
                String name = String.valueOf(vertex.get("name"));
                if (name.contains("Sink")) {
                    try {
                        totalChanges += sumVertexMetrics(jobId, vertexId, ".numRecordsOut");
                    } catch (Exception e) {
                        logger.debug("查询 Flink sink 指标失败: jobId={}, vertexId={}, error={}",
                                jobId, vertexId, e.getMessage());
                        changesUnavailable = true;
                    }
                }
                if (name.contains("Source")) {
                    // 毫秒 → 秒；查不到保持 -1
                    long lagMs = maxVertexMetric(jobId, vertexId, "currentEmitEventTimeLag");
                    if (lagMs >= 0) {
                        lagSeconds = lagMs / 1000;
                    }
                }
            }
        }
        return new long[]{lagSeconds, changesUnavailable ? -1 : totalChanges};
    }

    /** 列出 vertex 全部可用指标 id */
    @SuppressWarnings("unchecked")
    private List<String> listVertexMetricIds(String jobId, String vertexId) {
        List<Map<String, Object>> metrics = restClient.get()
                .uri(jobmanagerUrl + "/jobs/{jobId}/vertices/{vertexId}/metrics", jobId, vertexId)
                .retrieve()
                .body(List.class);
        if (metrics == null) {
            return List.of();
        }
        return metrics.stream().map(m -> String.valueOf(m.get("id"))).toList();
    }

    /** 按 id 批量查指标值（value 为字符串） */
    @SuppressWarnings("unchecked")
    private List<Long> queryVertexMetricValues(String jobId, String vertexId, List<String> metricIds) {
        if (metricIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> metrics = restClient.get()
                .uri(jobmanagerUrl + "/jobs/{jobId}/vertices/{vertexId}/metrics?get={get}",
                        jobId, vertexId, String.join(",", metricIds))
                .retrieve()
                .body(List.class);
        if (metrics == null) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> m : metrics) {
            try {
                values.add(Long.parseLong(String.valueOf(m.get("value"))));
            } catch (NumberFormatException e) {
                // 非数值指标（如 watermark）跳过
            }
        }
        return values;
    }

    /** 聚合 vertex 上所有 id 以 suffix 结尾的指标之和；查询失败抛异常（调用方据此跳过回写，防误清 0） */
    private long sumVertexMetrics(String jobId, String vertexId, String idSuffix) {
        List<String> ids = listVertexMetricIds(jobId, vertexId).stream()
                .filter(id -> id.endsWith(idSuffix))
                .toList();
        return queryVertexMetricValues(jobId, vertexId, ids).stream().mapToLong(Long::longValue).sum();
    }

    /** 取 vertex 上所有 id 以 suffix 结尾的指标最大值；无匹配/查询失败返回 -1 */
    private long maxVertexMetric(String jobId, String vertexId, String idSuffix) {
        try {
            List<String> ids = listVertexMetricIds(jobId, vertexId).stream()
                    .filter(id -> id.endsWith(idSuffix))
                    .toList();
            return queryVertexMetricValues(jobId, vertexId, ids).stream()
                    .mapToLong(Long::longValue).max().orElse(-1);
        } catch (Exception e) {
            logger.debug("查询 Flink vertex 指标失败: jobId={}, vertexId={}, suffix={}, error={}",
                    jobId, vertexId, idSuffix, e.getMessage());
            return -1;
        }
    }

    // ==================== Sprint 9 F1：吞吐（double）与 job-level 指标 ====================

    /**
     * 从作业概览提取吞吐量（行/秒）= 全部 sink vertex 的 numRecordsOutPerSecond 之和（跨子任务求和）。
     * <p>
     * M0 实测（Flink 2.2.1）：per-second 速率指标值是 double（如 0.0833...），现有 Long 解析路径会
     * 丢弃，故单独走 double 解析。任一下游指标查询失败返回 -1（调用方跳过回写，防误清 0）。
     */
    @SuppressWarnings("unchecked")
    public double extractThroughput(String jobId, Map<String, Object> jobOverview) {
        List<Map<String, Object>> vertices =
                (List<Map<String, Object>>) jobOverview.get("vertices");
        if (vertices == null) {
            return -1;
        }
        double total = 0;
        boolean unavailable = false;
        for (Map<String, Object> vertex : vertices) {
            if (String.valueOf(vertex.get("name")).contains("Sink")) {
                String vertexId = String.valueOf(vertex.get("id"));
                try {
                    total += sumVertexDoubleMetrics(jobId, vertexId, ".numRecordsOutPerSecond");
                } catch (Exception e) {
                    logger.debug("查询 Flink sink 吞吐指标失败: jobId={}, vertexId={}, error={}",
                            jobId, vertexId, e.getMessage());
                    unavailable = true;
                }
            }
        }
        return unavailable ? -1 : total;
    }

    /** 聚合 vertex 上所有 id 以 suffix 结尾的 double 指标之和（per-second 速率走 double 解析） */
    private double sumVertexDoubleMetrics(String jobId, String vertexId, String idSuffix) {
        List<String> ids = listVertexMetricIds(jobId, vertexId).stream()
                .filter(id -> id.endsWith(idSuffix))
                .toList();
        return queryVertexMetricDoubleValues(jobId, vertexId, ids).stream()
                .mapToDouble(Double::doubleValue).sum();
    }

    /** 按 id 批量查指标值并 double 解析（per-second 速率；非数值跳过） */
    @SuppressWarnings("unchecked")
    private List<Double> queryVertexMetricDoubleValues(String jobId, String vertexId, List<String> metricIds) {
        if (metricIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> metrics = restClient.get()
                .uri(jobmanagerUrl + "/jobs/{jobId}/vertices/{vertexId}/metrics?get={get}",
                        jobId, vertexId, String.join(",", metricIds))
                .retrieve()
                .body(List.class);
        if (metrics == null) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> m : metrics) {
            try {
                values.add(Double.parseDouble(String.valueOf(m.get("value"))));
            } catch (NumberFormatException e) {
                // 非数值指标跳过
            }
        }
        return values;
    }

    /**
     * 查询 job-level 指标（/jobs/{id}/metrics?get=...），返回指标 id → 值。
     * 查询失败抛异常（调用方按「作业不可达」处理）；单指标解析失败跳过。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Double> getJobMetrics(String jobId, List<String> metricIds) {
        if (metricIds == null || metricIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> metrics = restClient.get()
                .uri(jobmanagerUrl + "/jobs/{jobId}/metrics?get={get}", jobId, String.join(",", metricIds))
                .retrieve()
                .body(List.class);
        if (metrics == null) {
            return Map.of();
        }
        Map<String, Double> result = new java.util.HashMap<>();
        for (Map<String, Object> m : metrics) {
            try {
                result.put(String.valueOf(m.get("id")), Double.parseDouble(String.valueOf(m.get("value"))));
            } catch (NumberFormatException e) {
                // 非数值指标跳过
            }
        }
        return result;
    }
}
