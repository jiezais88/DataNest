package com.datanest.common.scheduler;

import com.datanest.common.constant.TaskTriggerType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PowerJob OpenAPI 通用调度客户端（XXL-JOB Admin REST 的替换实现）。
 * 对外保持原有类名/方法名，仅任务 ID 类型由 Integer 调整为 Long（PowerJob jobId 为 Long）。
 * 底层为纯 HTTP 调用，不依赖 powerjob-client，可被 engineering / governance / job 等服务共用。
 *
 * 语义对照：executorHandler → processorInfo（由消费方自定义 ProcessorFactory 路由到 handler Bean）、
 * jobGroup → appId（App 已预置：data-nest-job=1、data-nest-worker=2）、
 * timeout（秒）→ instanceTimeLimit（毫秒，0=不限）、failRetryCount → instanceRetryNum。
 * 注册时 jobParams 存业务实体 id（对齐 XXL executorParam=jobId）；调度触发读 jobParams，
 * 手动 runJob 传 instanceParams（消费方 dispatcher 负责 instanceParams 非空优先）。
 */
@Component
public class SchedulerClient {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerClient.class);

    private static final String PATH_ASSERT = "/openApi/assert";
    private static final String PATH_SAVE_JOB = "/openApi/saveJob";
    private static final String PATH_ENABLE_JOB = "/openApi/enableJob";
    private static final String PATH_DISABLE_JOB = "/openApi/disableJob";
    private static final String PATH_DELETE_JOB = "/openApi/deleteJob";
    private static final String PATH_RUN_JOB = "/openApi/runJob";
    private static final String PATH_FETCH_ALL_JOB = "/openApi/fetchAllJob";
    private static final String PATH_QUERY_JOB = "/openApi/queryJob";

    /** PowerJob 单机执行类型 */
    private static final String EXECUTE_TYPE_STANDALONE = "STANDALONE";
    /** PowerJob 内建 Java 处理器类型 */
    private static final String PROCESSOR_TYPE_BUILT_IN = "BUILT_IN";
    /** PowerJob 时间表达式类型：CRON 定时 */
    private static final String TIME_EXPRESSION_CRON = "CRON";
    /** PowerJob 时间表达式类型：仅 API 触发（对应 XXL scheduleType=NONE 的手动任务） */
    private static final String TIME_EXPRESSION_API = "API";

    @Value("${datanest.powerjob.server-address:http://middleware-powerjob:7700}")
    private String serverAddress;

    @Value("${datanest.powerjob.app-password:powerjob123}")
    private String appPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;

    /** appName → appId 本地缓存（App 已预置，启动后基本不变） */
    private final Map<String, Long> appIdCache = new ConcurrentHashMap<>();
    /** jobId → appId 本地缓存（注册/查询时填充，用于仅需 jobId 的启停删触发接口反查 appId） */
    private final Map<Long, Long> jobAppCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
    }

    /**
     * 注册调度任务，返回 PowerJob 任务 ID。
     * scheduleEnabled=true 且为 CRON 触发时按 cron 定时调度；手动触发任务登记为 API 类型。
     */
    public Long registerJob(String appName, String executorHandler, Long jobId, String name,
                            String cron, String triggerType, boolean scheduleEnabled,
                            int timeout, int failRetryCount) {
        Long appId = resolveAppId(appName);
        Map<String, Object> body = buildSaveJobRequest(null, appId, executorHandler, jobId, name,
                cron, triggerType, scheduleEnabled, timeout, failRetryCount);
        Long powerJobId = saveJob(body, "注册调度任务失败");
        jobAppCache.put(powerJobId, appId);
        logger.info("Registered PowerJob job: name={}, appId={}, jobId={}, businessId={}, start={}",
                name, appId, powerJobId, jobId, scheduleEnabled);
        return powerJobId;
    }

    /**
     * 更新调度任务（saveJob 带 id 全量覆盖）。
     */
    public void updateJob(Long powerJobId, String appName, String executorHandler, Long jobId, String name,
                          String cron, String triggerType, boolean scheduleEnabled,
                          int timeout, int failRetryCount) {
        Long appId = resolveAppId(appName);
        Map<String, Object> body = buildSaveJobRequest(powerJobId, appId, executorHandler, jobId, name,
                cron, triggerType, scheduleEnabled, timeout, failRetryCount);
        saveJob(body, "更新调度任务失败");
        jobAppCache.put(powerJobId, appId);
        logger.info("Updated PowerJob job: jobId={}, name={}, businessId={}, start={}", powerJobId, name, jobId, scheduleEnabled);
    }

    public void unregisterJob(Long powerJobId) {
        Long appId = resolveAppIdByJobId(powerJobId);
        postWithQuery(PATH_DELETE_JOB, queryOf("jobId", powerJobId, "appId", appId), "删除调度任务失败");
        jobAppCache.remove(powerJobId);
        logger.info("Removed PowerJob job: jobId={}", powerJobId);
    }

    public void triggerJob(Long powerJobId, String executorParam) {
        Long appId = resolveAppIdByJobId(powerJobId);
        Map<String, Object> query = queryOf("appId", appId, "jobId", powerJobId);
        // instanceParams 为手动触发参数，processor 侧按「instanceParams 非空优先，否则 jobParams」解析
        if (executorParam != null) {
            query.put("instanceParams", executorParam);
        }
        JsonNode response = postWithQuery(PATH_RUN_JOB, query, "触发调度任务失败");
        logger.info("Triggered PowerJob job: jobId={}, instanceId={}, instanceParams={}",
                powerJobId, response.path("data").asLong(-1), executorParam);
    }

    /**
     * @deprecated PowerJob 由 server 直接派发，无需等待执行器注册，waitExecutorReady 参数已忽略，仅为兼容保留。
     */
    @Deprecated
    public void triggerJob(Long powerJobId, String executorParam, boolean waitExecutorReady) {
        triggerJob(powerJobId, executorParam);
    }

    public void startJob(Long powerJobId) {
        Long appId = resolveAppIdByJobId(powerJobId);
        postWithQuery(PATH_ENABLE_JOB, queryOf("jobId", powerJobId, "appId", appId), "启动调度任务失败");
        logger.info("Started PowerJob job: jobId={}", powerJobId);
    }

    public void stopJob(Long powerJobId) {
        Long appId = resolveAppIdByJobId(powerJobId);
        postWithQuery(PATH_DISABLE_JOB, queryOf("jobId", powerJobId, "appId", appId), "停止调度任务失败");
        logger.info("Stopped PowerJob job: jobId={}", powerJobId);
    }

    /**
     * 兼容原 XXL 的 ensureJobGroup：PowerJob 的 App（对应原执行器分组）已预置，
     * 此处仅解析并返回 appId（带本地缓存），不做任何创建动作。
     */
    public Long ensureJobGroup(String appName) {
        return resolveAppId(appName);
    }

    /**
     * 按 appId + handler 名（processorInfo）查询已存在的任务，未找到返回 null。
     */
    public JsonNode findJobByHandler(Long appId, String executorHandler) {
        for (JsonNode job : fetchAllJobs(appId)) {
            if (executorHandler.equals(job.path("processorInfo").asText())) {
                return job;
            }
        }
        return null;
    }

    /**
     * 拉取指定 App 下全部任务（OpenAPI fetchAllJob），供调用方按 jobName 等字段自行过滤
     * （如 QualityCheckTriggerService 复用共享单规则任务）。
     */
    public List<JsonNode> fetchAllJob(String appName) {
        return fetchAllJobs(resolveAppId(appName));
    }

    /**
     * 平台定时任务 ensure 语义（供 JobRegistrar 使用）：按 jobName + appId 查找，
     * 存在则 saveJob 带 id 全量更新，否则新建；始终登记为 CRON + enable=true。
     *
     * @return PowerJob 任务 ID
     */
    public Long saveOrUpdateCronJob(String appName, String executorHandler, String jobName, String cron) {
        Long appId = resolveAppId(appName);
        Long existingId = null;
        for (JsonNode job : fetchAllJobs(appId)) {
            if (jobName.equals(job.path("jobName").asText())) {
                existingId = job.path("id").asLong();
                break;
            }
        }
        Map<String, Object> body = buildSaveJobRequest(existingId, appId, executorHandler, null, jobName,
                cron, TaskTriggerType.CRON.getCode(), true, 0, 0);
        Long powerJobId = saveJob(body, existingId == null ? "注册平台定时任务失败" : "更新平台定时任务失败");
        jobAppCache.put(powerJobId, appId);
        logger.info("Ensured PowerJob cron job: name={}, appId={}, jobId={}, cron={}, created={}",
                jobName, appId, powerJobId, cron, existingId == null);
        return powerJobId;
    }

    /**
     * 构造 saveJob 请求体。枚举字段（timeExpressionType/executeType/processorType）按字符串名传递。
     * timeout 单位换算：XXL 为秒、PowerJob instanceTimeLimit 为毫秒，0 表示不限制。
     */
    private Map<String, Object> buildSaveJobRequest(Long id, Long appId, String executorHandler,
                                                    Long jobId, String name, String cron, String triggerType,
                                                    boolean scheduleEnabled, int timeout, int failRetryCount) {
        boolean isCron = TaskTriggerType.CRON.getCode().equalsIgnoreCase(triggerType) && StringUtils.hasText(cron);
        Map<String, Object> body = new LinkedHashMap<>();
        if (id != null) {
            body.put("id", id);
        }
        body.put("jobName", name);
        body.put("jobDescription", name);
        body.put("appId", appId);
        // jobParams 存业务实体 id 字符串，对齐 XXL executorParam=jobId 语义
        body.put("jobParams", jobId == null ? "" : String.valueOf(jobId));
        // CRON 任务保留时间表达式（启停走 enable/disable，等价 XXL triggerStatus）；手动任务登记为 API 类型
        body.put("timeExpressionType", isCron ? TIME_EXPRESSION_CRON : TIME_EXPRESSION_API);
        body.put("timeExpression", isCron ? cron : "");
        body.put("executeType", EXECUTE_TYPE_STANDALONE);
        body.put("processorType", PROCESSOR_TYPE_BUILT_IN);
        body.put("processorInfo", executorHandler);
        body.put("instanceTimeLimit", timeout <= 0 ? 0L : timeout * 1000L);
        body.put("instanceRetryNum", Math.max(0, failRetryCount));
        body.put("taskRetryNum", 0);
        body.put("enable", !isCron || scheduleEnabled);
        return body;
    }

    /**
     * 解析 appName → appId（OpenAPI /assert，App 已预置不会自动创建），带本地缓存。
     */
    private Long resolveAppId(String appName) {
        Long cached = appIdCache.get(appName);
        if (cached != null) {
            return cached;
        }
        synchronized (appIdCache) {
            cached = appIdCache.get(appName);
            if (cached != null) {
                return cached;
            }
            JsonNode response = postWithQuery(PATH_ASSERT,
                    queryOf("appName", appName, "password", appPassword), "校验 PowerJob 应用失败");
            long appId = response.path("data").asLong(-1);
            if (appId <= 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "PowerJob 应用不存在: appName=" + appName);
            }
            appIdCache.put(appName, appId);
            return appId;
        }
    }

    /**
     * 仅持有 jobId 时反查所属 appId（enable/disable/delete/runJob 均强制要求 appId）。
     * 优先走本地缓存（注册/查询时填充），未命中再用 queryJob 按 idEq 精确查询。
     */
    private Long resolveAppIdByJobId(Long powerJobId) {
        Long cached = jobAppCache.get(powerJobId);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("idEq", powerJobId);
        JsonNode response = postWithJson(PATH_QUERY_JOB, query, "查询调度任务失败");
        JsonNode data = response.path("data");
        if (data.isArray() && !data.isEmpty()) {
            long appId = data.get(0).path("appId").asLong(-1);
            if (appId > 0) {
                jobAppCache.put(powerJobId, appId);
                return appId;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "PowerJob 任务不存在: jobId=" + powerJobId);
    }

    /**
     * 拉取指定 App 下全部任务（fetchAllJob），顺带回填 jobId→appId 缓存。
     */
    private List<JsonNode> fetchAllJobs(Long appId) {
        JsonNode response = postWithQuery(PATH_FETCH_ALL_JOB, queryOf("appId", appId), "查询任务列表失败");
        JsonNode data = response.path("data");
        List<JsonNode> jobs = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode job : data) {
                jobs.add(job);
                long id = job.path("id").asLong(-1);
                if (id > 0) {
                    jobAppCache.put(id, appId);
                }
            }
        }
        return jobs;
    }

    private Long saveJob(Map<String, Object> body, String errorMessage) {
        JsonNode response = postWithJson(PATH_SAVE_JOB, body, errorMessage);
        long id = response.path("data").asLong(-1);
        if (id <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": PowerJob 返回的任务 ID 无效");
        }
        return id;
    }

    private Map<String, Object> queryOf(Object... keyValues) {
        Map<String, Object> query = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            query.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return query;
    }

    /**
     * POST query param 风格请求（PowerJob OpenAPI 的 assert/enable/disable/delete/run/fetchAllJob 均为 query param）。
     * 注意：instanceParams 不能 URL 编码——PowerJob server 端按原样读取不解码（编码后 %2C 会原样传给 processor）。
     * instanceParams 内容为内部约定格式（逗号/冒号/数字），无 &、=、空格等需转义字符。
     */
    private JsonNode postWithQuery(String path, Map<String, Object> query, String errorMessage) {
        StringBuilder url = new StringBuilder(serverAddress).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (!first) {
                url.append('&');
            }
            String value = "instanceParams".equals(entry.getKey())
                    ? String.valueOf(entry.getValue())
                    : URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8);
            url.append(entry.getKey()).append('=').append(value);
            first = false;
        }
        return doPost(url.toString(), null, errorMessage);
    }

    /**
     * POST JSON body 风格请求（saveJob/queryJob）。
     * 显式序列化为字符串，避免依赖 RestTemplate 默认消息转换器的 Jackson 版本探测。
     */
    private JsonNode postWithJson(String path, Map<String, Object> body, String errorMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = objectMapper.writeValueAsString(body);
        return doPost(serverAddress + path, new HttpEntity<>(json, headers), errorMessage);
    }

    private JsonNode doPost(String url, HttpEntity<String> request, String errorMessage) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode result = objectMapper.readTree(response.getBody());
            return assertSuccess(result, errorMessage);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": " + e.getMessage(), e);
        }
    }

    /**
     * PowerJob ResultDTO 成功判定：success=true，失败时 message 携带原因。
     */
    private JsonNode assertSuccess(JsonNode response, String errorMessage) {
        if (response == null || response.isNull()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": 空响应");
        }
        if (!response.path("success").asBoolean(false)) {
            String message = response.path("message").asText("未知错误");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": " + message);
        }
        return response;
    }
}
