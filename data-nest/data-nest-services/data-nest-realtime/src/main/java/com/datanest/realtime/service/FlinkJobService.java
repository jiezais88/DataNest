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

        // 轮询 savepoint 触发结果（每 2s，最多 60s）
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
     * 查询失败返回 null（不影响状态回写主流程）。
     */
    @SuppressWarnings("unchecked")
    public String getJobRootException(String jobId) {
        try {
            Map<String, Object> exceptions = restClient.get()
                    .uri(jobmanagerUrl + "/jobs/{jobId}/exceptions", jobId)
                    .retrieve()
                    .body(Map.class);
            Object rootException = exceptions == null ? null : exceptions.get("root-exception");
            return rootException == null ? null : String.valueOf(rootException);
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
}
