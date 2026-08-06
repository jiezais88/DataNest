package com.datanest.engineering.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.config.DolphinSchedulerConfig;
import com.datanest.engineering.dto.DsApiResponse;
import com.datanest.engineering.dto.DsTaskDefinition;
import com.datanest.engineering.dto.DsTaskInstance;
import com.datanest.engineering.dto.DsWorkflowDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DolphinScheduler 3.4.2 客户端
 * 决策 ADR-S3-004：纯 HTTP RestTemplate，不引入 DS SDK
 * 决策 ADR-S3-008：内部接口鉴权（开发阶段不开启，但 token 已持久化）
 * 决策 ADR-S3-FJ：序列化使用 fastjson2
 * 所有方法遇到 code != 0 抛 BusinessException(DS_API_ERROR)
 */
@Service
public class DolphinSchedulerClient {

    private static final Logger logger = LoggerFactory.getLogger(DolphinSchedulerClient.class);

    private final DolphinSchedulerConfig config;
    private final RestTemplate restTemplate;

    public DolphinSchedulerClient(DolphinSchedulerConfig config,
                                  @Qualifier("dolphinSchedulerRestTemplate") RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    private String url(String path) {
        String base = config.getApiUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (config.getToken() != null && !config.getToken().isEmpty()) {
            headers.set("token", config.getToken());
        }
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getToken() != null && !config.getToken().isEmpty()) {
            headers.set("token", config.getToken());
        }
        return headers;
    }

    private <T> DsApiResponse<T> exchangeForDsResponse(String method, String fullUrl, HttpEntity<?> entity, TypeReference<DsApiResponse<T>> typeRef) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(fullUrl, HttpMethod.valueOf(method), entity, String.class);
            String body = resp.getBody();
            if (body == null) {
                throw new BusinessException(ErrorCode.DS_API_ERROR, "DS API 返回空 body: " + fullUrl);
            }
            return JSON.parseObject(body, typeRef);
        } catch (BusinessException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            logger.error("DS API HTTP 错误: url={}, status={}, body={}", fullUrl, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.DS_API_ERROR,
                    "DS HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("DS API 调用失败: url={}", fullUrl, e);
            throw new BusinessException(ErrorCode.DS_API_ERROR, "DS 调用失败: " + e.getMessage());
        }
    }

    private <T> DsApiResponse<T> exchangeForDsResponse(String method, String fullUrl, HttpEntity<?> entity, Class<T> dataType) {
        String body = exchangeForString(method, fullUrl, entity);
        // Class<T> 无法通过匿名 TypeReference 捕获嵌套泛型，先解析外层，再转换 data
        DsApiResponse<?> raw = JSON.parseObject(body, DsApiResponse.class);
        if (raw == null) {
            throw new BusinessException(ErrorCode.DS_API_ERROR, "DS API 返回空 body: " + fullUrl);
        }
        DsApiResponse<T> typed = new DsApiResponse<>();
        typed.setCode(raw.getCode());
        typed.setMsg(raw.getMsg());
        typed.setFailed(raw.getFailed());
        typed.setSuccess(raw.getSuccess());
        Object data = raw.getData();
        if (data != null) {
            if (dataType.isInstance(data)) {
                typed.setData(dataType.cast(data));
            } else {
                typed.setData(JSON.parseObject(JSON.toJSONString(data), dataType));
            }
        }
        return typed;
    }

    private <T> T executeAndRequire(String method, String fullUrl, HttpEntity<?> entity, TypeReference<DsApiResponse<T>> typeRef) {
        DsApiResponse<T> resp = exchangeForDsResponse(method, fullUrl, entity, typeRef);
        if (!resp.isOk()) {
            throw new BusinessException(ErrorCode.DS_API_ERROR,
                    "DS 业务错误: code=" + resp.getCode() + ", msg=" + resp.getMsg());
        }
        return resp.getData();
    }

    private <T> T executeAndRequire(String method, String fullUrl, HttpEntity<?> entity, Class<T> dataType) {
        DsApiResponse<T> resp = exchangeForDsResponse(method, fullUrl, entity, dataType);
        if (!resp.isOk()) {
            throw new BusinessException(ErrorCode.DS_API_ERROR,
                    "DS 业务错误: code=" + resp.getCode() + ", msg=" + resp.getMsg());
        }
        return resp.getData();
    }

    private String exchangeForString(String method, String fullUrl, HttpEntity<?> entity) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(fullUrl, HttpMethod.valueOf(method), entity, String.class);
            String body = resp.getBody();
            if (body == null) {
                throw new BusinessException(ErrorCode.DS_API_ERROR, "DS API 返回空 body: " + fullUrl);
            }
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            logger.error("DS API HTTP 错误: url={}, status={}, body={}", fullUrl, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.DS_API_ERROR,
                    "DS HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("DS API 调用失败: url={}", fullUrl, e);
            throw new BusinessException(ErrorCode.DS_API_ERROR, "DS 调用失败: " + e.getMessage());
        }
    }

    // ============================================
    // 1. 用户/Token
    // ============================================

    /**
     * 登录获取 sessionId（仅在 token 失效时备用；日常直接用持久 token）
     */
    public String login(String userName, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("userName", userName);
        form.add("userPassword", password);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, jsonHeaders());
        // login 接口返回结构是 {code,msg,data:{sessionId,...}}
        Map<?, ?> data = executeAndRequire("POST", url("/login"), entity, Map.class);
        Object sessionId = data == null ? null : data.get("sessionId");
        return sessionId == null ? null : sessionId.toString();
    }

    // ============================================
    // 2. Workflow Definition CRUD
    // ============================================

    /**
     * 创建工作流定义
     * @return DS 返回的 workflow code
     */
    public Long createWorkflowDefinition(Long projectCode, String name, String description,
                                         List<DsTaskDefinition> taskDefs, String taskRelationJson,
                                         String locationsJson, String globalParams,
                                         String executionType, Integer timeout) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", name);
        form.add("description", description == null ? "" : description);
        form.add("globalParams", globalParams == null ? "[]" : globalParams);
        form.add("locations", locationsJson);
        form.add("timeout", String.valueOf(timeout == null ? 0 : timeout));
        form.add("taskRelationJson", taskRelationJson);
        form.add("taskDefinitionJson", serializeJson(taskDefs));
        form.add("otherParamsJson", "");
        form.add("executionType", executionType == null ? "PARALLEL" : executionType);

        logger.info("DS createWorkflowDefinition payload: name={}, taskDefs={}, locations={}, relations={}, taskParamsSample={}",
                name, serializeJson(taskDefs), locationsJson, taskRelationJson,
                taskDefs.isEmpty() ? "[]" : taskDefs.get(0).getTaskParams());

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        // DS 返回 {id, code, name, ...}
        DsWorkflowDefinition created = executeAndRequire("POST",
                url("/projects/" + projectCode + "/workflow-definition"),
                entity, DsWorkflowDefinition.class);
        return created.getCode();
    }

    /**
     * 更新工作流定义（PUT，需要先 offline）
     */
    public Long updateWorkflowDefinition(Long projectCode, Long code, String name, String description,
                                         List<DsTaskDefinition> taskDefs, String taskRelationJson,
                                         String locationsJson, String globalParams,
                                         String executionType, Integer timeout) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", name);
        form.add("description", description == null ? "" : description);
        form.add("globalParams", globalParams == null ? "[]" : globalParams);
        form.add("locations", locationsJson);
        form.add("timeout", String.valueOf(timeout == null ? 0 : timeout));
        form.add("taskRelationJson", taskRelationJson);
        form.add("taskDefinitionJson", serializeJson(taskDefs));
        form.add("otherParamsJson", "");
        form.add("executionType", executionType == null ? "PARALLEL" : executionType);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        DsWorkflowDefinition updated = executeAndRequire("PUT",
                url("/projects/" + projectCode + "/workflow-definition/" + code),
                entity, DsWorkflowDefinition.class);
        return updated.getCode();
    }

    /**
     * 发布 / 下线
     * @param releaseState "ONLINE" 或 "OFFLINE"
     */
    public void releaseWorkflow(Long projectCode, Long code, String name, String releaseState) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("name", name);
        form.add("releaseState", releaseState);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        executeAndRequire("POST",
                url("/projects/" + projectCode + "/workflow-definition/" + code + "/release"),
                entity, Boolean.class);
    }

    /**
     * 删除工作流定义
     */
    public void deleteWorkflow(Long projectCode, Long code) {
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        executeAndRequire("DELETE",
                url("/projects/" + projectCode + "/workflow-definition/" + code),
                entity, Object.class);
    }

    /**
     * 获取工作流定义详情（含 task list）
     */
    public DsWorkflowDefinition getWorkflowDefinition(Long projectCode, Long code) {
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        return executeAndRequire("GET",
                url("/projects/" + projectCode + "/workflow-definition/" + code),
                entity, DsWorkflowDefinition.class);
    }

    /**
     * 列出项目下所有工作流
     */
    public List<DsWorkflowDefinition> listWorkflowDefinitions(Long projectCode) {
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        return executeAndRequire("GET",
                url("/projects/" + projectCode + "/workflow-definition/list"),
                entity, List.class);
    }

    // ============================================
    // 3. 触发执行
    // ============================================

    /**
     * 触发工作流执行
     * @return DS workflow instance id
     */
    public Long startWorkflowInstance(Long projectCode, Long workflowDefinitionCode,
                                      String failureStrategy, String execType,
                                      Long execUserId, String scheduleTime) {
        return startWorkflowInstance(projectCode, workflowDefinitionCode,
                failureStrategy, execType, execUserId, scheduleTime, List.of());
    }

    public Long startWorkflowInstance(Long projectCode, Long workflowDefinitionCode,
                                      String failureStrategy, String execType,
                                      Long execUserId, String scheduleTime,
                                      List<Long> startNodeCodes) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("workflowDefinitionCode", String.valueOf(workflowDefinitionCode));
        form.add("failureStrategy", failureStrategy == null ? "END" : failureStrategy);
        form.add("execType", execType == null ? "START_PROCESS" : execType);
        form.add("startNodeList", startNodeCodes == null || startNodeCodes.isEmpty()
                ? "" : startNodeCodes.stream().map(String::valueOf).collect(Collectors.joining(",")));
        form.add("taskDependType", "TASK_POST");
        form.add("runMode", "RUN_MODE_SERIAL");
        form.add("warningType", "NONE");
        form.add("warningGroupId", "0");
        form.add("execUserId", String.valueOf(execUserId == null ? 1 : execUserId));
        form.add("tenantCode", config.getTenantCode() == null ? "default" : config.getTenantCode());
        form.add("scheduleTime", scheduleTime == null ? "" : scheduleTime);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        // 返回 data 是 Long[]
        List<Number> result = executeAndRequire("POST",
                url("/projects/" + projectCode + "/executors/start-workflow-instance"),
                entity, List.class);
        if (result == null || result.isEmpty()) {
            throw new BusinessException(ErrorCode.DS_API_ERROR, "DS 触发工作流返回空 id");
        }
        return result.get(0).longValue();
    }

    // ============================================
    // 4. 查询
    // ============================================

    /**
     * 查询工作流实例（执行历史）分页
     */
    public Map<String, Object> listWorkflowInstances(Long projectCode, int pageNo, int pageSize) {
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        String fullUrl = url("/projects/" + projectCode + "/workflow-instances")
                + "?pageNo=" + pageNo + "&pageSize=" + pageSize;
        return executeAndRequire("GET", fullUrl, entity, Map.class);
    }

    /**
     * 查询工作流实例的 task instances
     */
    public List<DsTaskInstance> listTaskInstances(Long projectCode, Long workflowInstanceId) {
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        // 用 TypeReference 保留 totalList 的 Object 类型，调用方再按需解析
        DsApiResponse<JSONObject> resp = exchangeForDsResponse("GET",
                url("/projects/" + projectCode + "/workflow-instances/" + workflowInstanceId + "/tasks"),
                entity, new TypeReference<DsApiResponse<JSONObject>>() {
                });
        if (!resp.isOk() || resp.getData() == null) {
            return List.of();
        }
        JSONObject data = resp.getData();
        // DS 实际返回 { totalList: [...], total: N, ... }；用 JSONObject.getJSONArray 拿原始 list
        com.alibaba.fastjson2.JSONArray arr = data.getJSONArray("totalList");
        if (arr == null || arr.isEmpty()) {
            return List.of();
        }
        return arr.toJavaList(DsTaskInstance.class);
    }

    /**
     * 停止工作流实例。
     * DS 3.4.2 的正确端点是 ExecutorController.controlWorkflowInstance：
     * POST /projects/{code}/executors/execute?workflowInstanceId={id}&executeType=STOP
     * （旧实现 POST /workflow-instances/{id} 在 DS 3.4.2 无映射，返回 405）
     */
    public void stopWorkflowInstance(Long projectCode, Long workflowInstanceId) {
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        executeAndRequire("POST",
                url("/projects/" + projectCode + "/executors/execute?workflowInstanceId="
                        + workflowInstanceId + "&executeType=STOP"),
                entity, Object.class);
    }

    // ============================================
    // 5. 调度 (CRON)
    // ============================================

    /**
     * 创建/更新 Cron 调度，并上线。
     * - scheduleId 为空：创建新 schedule
     * - scheduleId 非空：更新已有 schedule
     * 创建/更新后统一调用 /online 上线，确保 DS 端实际触发。
     */
    public Long createOrUpdateSchedule(Long projectCode, Long scheduleId, Long workflowDefinitionCode, String schedule) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("schedule", schedule);     // JSON 字符串：{"crontab":"0 0 * * * ?","startTime":"...","endTime":"..."}
        form.add("warningType", "NONE");
        form.add("warningGroupId", "0");
        form.add("failureStrategy", "END");
        form.add("tenantCode", config.getTenantCode() == null ? "default" : config.getTenantCode());
        form.add("userId", "1");

        Long resultId;
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        if (scheduleId != null) {
            // DS 在线状态不允许直接修改，需要先 offline -> update -> online
            offlineSchedule(projectCode, scheduleId);
            logger.info("调用 DS schedule API: PUT /projects/{}/schedules/{}, schedule={}",
                    projectCode, scheduleId, schedule);
            Map<?, ?> data = executeAndRequire("PUT",
                    url("/projects/" + projectCode + "/schedules/" + scheduleId),
                    entity, Map.class);
            logger.info("DS schedule API 响应: projectCode={}, scheduleId={}, data={}",
                    projectCode, scheduleId, data);
            Object id = data == null ? null : data.get("id");
            resultId = id == null ? scheduleId : Long.parseLong(id.toString());
        } else {
            form.add("workflowDefinitionCode", String.valueOf(workflowDefinitionCode));
            logger.info("调用 DS schedule API: POST /projects/{}/schedules, workflowDefinitionCode={}, schedule={}",
                    projectCode, workflowDefinitionCode, schedule);
            Map<?, ?> data = executeAndRequire("POST",
                    url("/projects/" + projectCode + "/schedules"),
                    entity, Map.class);
            logger.info("DS schedule API 响应: projectCode={}, workflowDefinitionCode={}, data={}",
                    projectCode, workflowDefinitionCode, data);
            Object id = data == null ? null : data.get("id");
            resultId = id == null ? null : Long.parseLong(id.toString());
        }

        if (resultId != null) {
            onlineSchedule(projectCode, resultId);
        }
        return resultId;
    }

    /**
     * 上线 schedule，使 cron 真正生效。
     */
    public void onlineSchedule(Long projectCode, Long scheduleId) {
        logger.info("调用 DS schedule online API: POST /projects/{}/schedules/{}/online", projectCode, scheduleId);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(scheduleId));
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        Object resp = executeAndRequire("POST",
                url("/projects/" + projectCode + "/schedules/" + scheduleId + "/online"),
                entity, Object.class);
        logger.info("DS schedule online API 响应: projectCode={}, scheduleId={}, resp={}", projectCode, scheduleId, resp);
    }

    public void deleteSchedule(Long projectCode, Long scheduleId) {
        // DS 要求 schedule 必须先 offline 才能删除
        offlineSchedule(projectCode, scheduleId);
        logger.info("调用 DS deleteSchedule API: DELETE /projects/{}/schedules/{}", projectCode, scheduleId);
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        Object resp = executeAndRequire("DELETE",
                url("/projects/" + projectCode + "/schedules/" + scheduleId),
                entity, Object.class);
        logger.info("DS deleteSchedule API 响应: projectCode={}, scheduleId={}, resp={}", projectCode, scheduleId, resp);
    }

    /**
     * 下线 schedule。
     */
    public void offlineSchedule(Long projectCode, Long scheduleId) {
        logger.info("调用 DS schedule offline API: POST /projects/{}/schedules/{}/offline", projectCode, scheduleId);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(scheduleId));
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        try {
            Object resp = executeAndRequire("POST",
                    url("/projects/" + projectCode + "/schedules/" + scheduleId + "/offline"),
                    entity, Object.class);
            logger.info("DS schedule offline API 响应: projectCode={}, scheduleId={}, resp={}", projectCode, scheduleId, resp);
        } catch (BusinessException e) {
            // 已是 offline 时忽略（DS 可能返回类似 'already offline'）
            if (e.getMessage() != null && e.getMessage().contains("already offline")) {
                logger.info("DS schedule 已处于 offline，跳过: scheduleId={}", scheduleId);
            } else {
                throw e;
            }
        }
    }

    // ============================================
    // 6. DS Project CRUD（Sprint 3 P0-3 / P2-4）
    // ============================================

    /**
     * 在 DS 创建项目，返回 project code
     * Sprint 3 P0-3：DagProjectService 调这里真建项目，不再写死 fallback
     * 注：DS 3.4.2 创建项目需要 admin 权限；当前 token 是 admin/dolphinscheduler123 拿的，长期有效
     */
    public Long createProject(String name, String description) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("projectName", name);
        form.add("description", description == null ? "" : description);
        form.add("userName", "admin");
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, authHeaders());
        Map<?, ?> data = executeAndRequire("POST",
                url("/projects"), entity, Map.class);
        Object code = data == null ? null : data.get("code");
        if (code == null) {
            throw new BusinessException(ErrorCode.DS_API_ERROR, "DS 创建项目返回 code 为空");
        }
        return Long.parseLong(code.toString());
    }

    /**
     * 删除 DS 项目（P2-4：DagProject.delete 同步清理）
     * 注：DS 3.4.2 DELETE /projects/{code} — code 是项目 code 不是 id
     */
    public void deleteProject(Long projectCode) {
        if (projectCode == null) return;
        HttpEntity<?> entity = new HttpEntity<>(authHeaders());
        executeAndRequire("DELETE",
                url("/projects/" + projectCode),
                entity, Object.class);
    }

    // ============================================
    // helpers
    // ============================================

    private String serializeJson(Object obj) {
        return JSON.toJSONString(obj);
    }

    public DolphinSchedulerConfig getConfig() {
        return config;
    }
}
