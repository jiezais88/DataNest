package com.datanest.common.scheduler;

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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PowerJob 工作流（Workflow/DAG）OpenAPI 客户端，DolphinScheduler DAG 编排的替换实现。
 * 与 {@link SchedulerClient} 同套配置与 HTTP 直连思路（纯 RestTemplate，不依赖 powerjob-client）。
 *
 * 语义对照：DS workflow → PowerJob workflow（saveWorkflow + PEWorkflowDAG 点线表示法）、
 * DS 节点任务 → timeExpressionType=WORKFLOW 的 JOB（由工作流驱动，不独立调度）。
 * cron 为空时工作流登记为 API 类型（仅手动/触发运行），非空登记为 CRON 定时。
 *
 * 注意（5.1.2 server 实测）：saveWorkflow 的 dag.nodes[].nodeId 必须是 server 侧已存在的
 * 工作流节点记录 ID（workflow_node_info 表，经 /openApi/addWorkflowNode 创建），
 * 否则 server 报 "can't find node info by id"。
 */
@Component
public class PowerJobWorkflowClient {

    private static final Logger logger = LoggerFactory.getLogger(PowerJobWorkflowClient.class);

    private static final String PATH_ASSERT = "/openApi/assert";
    private static final String PATH_SAVE_JOB = "/openApi/saveJob";
    private static final String PATH_DELETE_JOB = "/openApi/deleteJob";
    private static final String PATH_SAVE_WORKFLOW = "/openApi/saveWorkflow";
    private static final String PATH_SAVE_WORKFLOW_NODE = "/openApi/addWorkflowNode";
    private static final String PATH_RUN_WORKFLOW = "/openApi/runWorkflow";
    private static final String PATH_FETCH_WF_INSTANCE_INFO = "/openApi/fetchWfInstanceInfo";
    private static final String PATH_STOP_WF_INSTANCE = "/openApi/stopWfInstance";
    private static final String PATH_RETRY_WF_INSTANCE = "/openApi/retryWfInstance";
    private static final String PATH_ENABLE_WORKFLOW = "/openApi/enableWorkflow";
    private static final String PATH_DISABLE_WORKFLOW = "/openApi/disableWorkflow";
    private static final String PATH_DELETE_WORKFLOW = "/openApi/deleteWorkflow";

    /** PowerJob 单机执行类型 */
    private static final String EXECUTE_TYPE_STANDALONE = "STANDALONE";
    /** PowerJob 内建 Java 处理器类型 */
    private static final String PROCESSOR_TYPE_BUILT_IN = "BUILT_IN";
    /** PowerJob 时间表达式类型：CRON 定时 */
    private static final String TIME_EXPRESSION_CRON = "CRON";
    /** PowerJob 时间表达式类型：仅 API 触发 */
    private static final String TIME_EXPRESSION_API = "API";
    /** PowerJob 时间表达式类型：工作流节点任务（由 workflow 驱动，不独立调度） */
    private static final String TIME_EXPRESSION_WORKFLOW = "WORKFLOW";

    @Value("${datanest.powerjob.server-address:http://middleware-powerjob:7700}")
    private String serverAddress;

    @Value("${datanest.powerjob.app-password:powerjob123}")
    private String appPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;

    /** appName → appId 本地缓存（App 已预置，启动后基本不变） */
    private final Map<String, Long> appIdCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
    }

    /**
     * 注册 DAG 节点对应的 JOB（WORKFLOW 时间类型，由工作流驱动），返回 PowerJob 任务 ID。
     * processorInfo=handler，由消费方自定义 ProcessorFactory 路由到对应处理器 Bean。
     */
    public Long saveNodeJob(String appName, String handler, String jobName, String jobParams) {
        return saveNodeJob(appName, null, handler, jobName, jobParams);
    }

    /**
     * 注册/更新 DAG 节点对应的 JOB（WORKFLOW 时间类型，由工作流驱动），返回 PowerJob 任务 ID。
     * jobId 为空新建，非空按 id 更新（saveJob 幂等语义）。
     * processorInfo=handler，由消费方自定义 ProcessorFactory 路由到对应处理器 Bean。
     */
    public Long saveNodeJob(String appName, Long jobId, String handler, String jobName, String jobParams) {
        Long appId = resolveAppId(appName);
        Map<String, Object> body = new LinkedHashMap<>();
        if (jobId != null) {
            body.put("id", jobId);
        }
        body.put("jobName", jobName);
        body.put("jobDescription", jobName);
        body.put("appId", appId);
        body.put("jobParams", jobParams == null ? "" : jobParams);
        body.put("timeExpressionType", TIME_EXPRESSION_WORKFLOW);
        body.put("timeExpression", "");
        body.put("executeType", EXECUTE_TYPE_STANDALONE);
        body.put("processorType", PROCESSOR_TYPE_BUILT_IN);
        body.put("processorInfo", handler);
        body.put("instanceTimeLimit", 0L);
        body.put("instanceRetryNum", 0);
        body.put("taskRetryNum", 0);
        body.put("enable", true);
        JsonNode response = postWithJson(PATH_SAVE_JOB, body, "注册工作流节点任务失败");
        long id = response.path("data").asLong(-1);
        if (id <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "注册工作流节点任务失败: PowerJob 返回的任务 ID 无效");
        }
        logger.info("Saved PowerJob workflow node job: name={}, appId={}, jobId={}, handler={}", jobName, appId, id, handler);
        return id;
    }

    /**
     * 软删除 DAG 节点对应的 JOB（server 端为软删）。
     */
    public void deleteNodeJob(String appName, Long jobId) {
        Long appId = resolveAppId(appName);
        postWithQuery(PATH_DELETE_JOB, queryOf("jobId", jobId, "appId", appId), "删除工作流节点任务失败");
        logger.info("Deleted PowerJob workflow node job: jobId={}, appId={}", jobId, appId);
    }

    /**
     * 注册/更新 server 侧工作流节点记录（workflow_node_info 表），返回节点记录 ID。
     * nodeInfoId 为空新建，非空按 id 更新。
     * saveWorkflow 的 dag.nodes[].nodeId 必须引用此处返回的节点记录 ID，
     * 否则 server 报 "can't find node info by id"。
     *
     * @param nodeType 节点类型：1=JOB、2=DECISION、3=NESTED_WORKFLOW
     */
    public Long saveWorkflowNode(String appName, Long nodeInfoId, Integer nodeType, Long jobId,
                                 String nodeName, String nodeParams, Boolean skipWhenFailed) {
        Long appId = resolveAppId(appName);
        Map<String, Object> node = new LinkedHashMap<>();
        if (nodeInfoId != null) {
            node.put("id", nodeInfoId);
        }
        node.put("appId", appId);
        node.put("type", nodeType);
        node.put("jobId", jobId);
        node.put("nodeName", nodeName);
        node.put("nodeParams", nodeParams == null ? "" : nodeParams);
        node.put("enable", true);
        node.put("skipWhenFailed", skipWhenFailed != null && skipWhenFailed);
        // server 接口接收 List<SaveWorkflowNodeRequest>，返回 List<WorkflowNodeInfoDO>，取第一个元素的 id
        JsonNode response = postWithJson(PATH_SAVE_WORKFLOW_NODE, List.of(node), "注册工作流节点失败");
        long id = response.path("data").path(0).path("id").asLong(-1);
        if (id <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "注册工作流节点失败: PowerJob 返回的节点 ID 无效");
        }
        logger.info("Saved PowerJob workflow node: name={}, appId={}, nodeId={}, jobId={}, type={}", nodeName, appId, id, jobId, nodeType);
        return id;
    }

    /**
     * 新建/更新工作流。workflowId 为空新建，非空按 id 全量更新。
     * cron 非空登记为 CRON 定时，否则登记为 API 类型（仅手动触发）。
     *
     * @return PowerJob 工作流 ID
     */
    public Long saveWorkflow(String appName, Long workflowId, String wfName, String cron, boolean enable, PJDag dag) {
        Long appId = resolveAppId(appName);
        boolean isCron = StringUtils.hasText(cron);
        Map<String, Object> body = new LinkedHashMap<>();
        if (workflowId != null) {
            body.put("id", workflowId);
        }
        body.put("wfName", wfName);
        body.put("wfDescription", wfName);
        body.put("appId", appId);
        body.put("timeExpressionType", isCron ? TIME_EXPRESSION_CRON : TIME_EXPRESSION_API);
        body.put("timeExpression", isCron ? cron : "");
        body.put("maxWfInstanceNum", 1);
        body.put("enable", enable);
        body.put("dag", dag);
        JsonNode response = postWithJson(PATH_SAVE_WORKFLOW, body, workflowId == null ? "新建工作流失败" : "更新工作流失败");
        long id = response.path("data").asLong(-1);
        if (id <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存工作流失败: PowerJob 返回的工作流 ID 无效");
        }
        logger.info("Saved PowerJob workflow: name={}, appId={}, workflowId={}, cron={}, enable={}", wfName, appId, id, cron, enable);
        return id;
    }

    /**
     * 手动触发工作流运行一次，返回工作流实例 ID（wfInstanceId）。
     */
    public Long runWorkflow(String appName, Long workflowId, String initParams) {
        Long appId = resolveAppId(appName);
        Map<String, Object> query = queryOf("workflowId", workflowId, "appId", appId);
        if (initParams != null) {
            query.put("initParams", initParams);
        }
        JsonNode response = postWithQuery(PATH_RUN_WORKFLOW, query, "触发工作流失败");
        long wfInstanceId = response.path("data").asLong(-1);
        logger.info("Triggered PowerJob workflow: workflowId={}, wfInstanceId={}, initParams={}", workflowId, wfInstanceId, initParams);
        return wfInstanceId;
    }

    /**
     * 查询工作流实例详情，返回 data 节点的完整 JSON（WorkflowInstanceInfoDTO）。
     * 字段含 status（WfInstanceStatus 数字码）、dag（PEWorkflowDAG 的 JSON 字符串，
     * 调用方需二次解析后取 nodes[].nodeId/status/instanceId）、wfInstanceId、result 等。
     */
    public JsonNode fetchWfInstanceInfo(String appName, Long wfInstanceId) {
        Long appId = resolveAppId(appName);
        JsonNode response = postWithQuery(PATH_FETCH_WF_INSTANCE_INFO,
                queryOf("wfInstanceId", wfInstanceId, "appId", appId), "查询工作流实例失败");
        return response.path("data");
    }

    public void stopWfInstance(String appName, Long wfInstanceId) {
        Long appId = resolveAppId(appName);
        postWithQuery(PATH_STOP_WF_INSTANCE, queryOf("wfInstanceId", wfInstanceId, "appId", appId), "停止工作流实例失败");
        logger.info("Stopped PowerJob workflow instance: wfInstanceId={}", wfInstanceId);
    }

    public void retryWfInstance(String appName, Long wfInstanceId) {
        Long appId = resolveAppId(appName);
        postWithQuery(PATH_RETRY_WF_INSTANCE, queryOf("wfInstanceId", wfInstanceId, "appId", appId), "重试工作流实例失败");
        logger.info("Retried PowerJob workflow instance: wfInstanceId={}", wfInstanceId);
    }

    public void enableWorkflow(String appName, Long workflowId) {
        Long appId = resolveAppId(appName);
        postWithQuery(PATH_ENABLE_WORKFLOW, queryOf("workflowId", workflowId, "appId", appId), "启用工作流失败");
        logger.info("Enabled PowerJob workflow: workflowId={}", workflowId);
    }

    public void disableWorkflow(String appName, Long workflowId) {
        Long appId = resolveAppId(appName);
        postWithQuery(PATH_DISABLE_WORKFLOW, queryOf("workflowId", workflowId, "appId", appId), "停用工作流失败");
        logger.info("Disabled PowerJob workflow: workflowId={}", workflowId);
    }

    public void deleteWorkflow(String appName, Long workflowId) {
        Long appId = resolveAppId(appName);
        postWithQuery(PATH_DELETE_WORKFLOW, queryOf("workflowId", workflowId, "appId", appId), "删除工作流失败");
        logger.info("Deleted PowerJob workflow: workflowId={}", workflowId);
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

    private Map<String, Object> queryOf(Object... keyValues) {
        Map<String, Object> query = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            query.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return query;
    }

    /**
     * POST query param 风格请求（assert/runWorkflow/启停删/fetchWfInstanceInfo 均为 query param）。
     * 所有参数统一 URLEncoder 编码一次（server 端 Spring 会正常解码，initParams 传 JSON 已实测无问题），
     * 并走 URI 形式提交，避免 RestTemplate 对 String URL 做 URI 模板展开导致双编码/大括号解析异常。
     */
    private JsonNode postWithQuery(String path, Map<String, Object> query, String errorMessage) {
        StringBuilder url = new StringBuilder(serverAddress).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (!first) {
                url.append('&');
            }
            url.append(entry.getKey()).append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
            first = false;
        }
        return doPost(URI.create(url.toString()), null, errorMessage);
    }

    /**
     * POST JSON body 风格请求（saveJob/saveWorkflow/saveWorkflowNode）。
     * body 可为 Map 或 List（saveWorkflowNode 接收 List<SaveWorkflowNodeRequest>）。
     * 显式序列化为字符串，避免依赖 RestTemplate 默认消息转换器的 Jackson 版本探测。
     */
    private JsonNode postWithJson(String path, Object body, String errorMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = objectMapper.writeValueAsString(body);
        return doPost(URI.create(serverAddress + path), new HttpEntity<>(json, headers), errorMessage);
    }

    private JsonNode doPost(URI uri, HttpEntity<String> request, String errorMessage) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(uri, request, String.class);
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
