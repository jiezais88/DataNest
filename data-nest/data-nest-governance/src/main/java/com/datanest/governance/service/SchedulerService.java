package com.datanest.governance.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class SchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerService.class);
    private static final String DEFAULT_AUTHOR = "data-nest";
    private static final String HANDLER_NAME = "collectTaskHandler";
    private static final String GLUE_TYPE_BEAN = "BEAN";
    private static final String ROUTE_STRATEGY = "ROUND";
    private static final String MISFIRE_STRATEGY = "DO_NOTHING";
    private static final String BLOCK_STRATEGY = "SERIAL_EXECUTION";

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.executor.appname}")
    private String appName;

    @Value("${datanest.governance.xxl-job.username:admin}")
    private String adminUsername;

    @Value("${datanest.governance.xxl-job.password:123456}")
    private String adminPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;
    private String sessionCookie;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(factory);
        System.setProperty("http.followRedirects", "false");
    }

    public Integer registerJob(Long taskId, String name, String cronExpression, String scheduleType, boolean start) {
        int jobGroup = ensureJobGroup();
        MultiValueMap<String, String> params = buildJobParams(null, jobGroup, name, cronExpression, scheduleType, true, taskId, start);
        JsonNode response = postWithAuth("/jobinfo/insert", params, "注册调度任务失败");
        Integer jobId = parseJobId(response);
        logger.info("Registered XXL-JOB job: name={}, jobGroup={}, jobId={}, taskId={}, start={}", name, jobGroup, jobId, taskId, start);
        return jobId;
    }

    public void updateJob(Integer jobId, Long taskId, String name, String cronExpression, String scheduleType, boolean start) {
        int jobGroup = ensureJobGroup();
        MultiValueMap<String, String> params = buildJobParams(jobId, jobGroup, name, cronExpression, scheduleType, false, taskId, start);
        params.add("id", String.valueOf(jobId));
        postWithAuth("/jobinfo/update", params, "更新调度任务失败");
        logger.info("Updated XXL-JOB job: jobId={}, name={}, taskId={}, start={}", jobId, name, taskId, start);
    }

    public void unregisterJob(Integer jobId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("ids[]", String.valueOf(jobId));
        postWithAuth("/jobinfo/delete", params, "删除调度任务失败");
        logger.info("Removed XXL-JOB job: jobId={}", jobId);
    }

    public void startJob(Integer jobId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("id", String.valueOf(jobId));
        postWithAuth("/jobinfo/start", params, "启动调度任务失败");
        logger.info("Started XXL-JOB job: jobId={}", jobId);
    }

    public void stopJob(Integer jobId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("id", String.valueOf(jobId));
        postWithAuth("/jobinfo/stop", params, "停止调度任务失败");
        logger.info("Stopped XXL-JOB job: jobId={}", jobId);
    }

    public void triggerJob(Integer jobId, String executorParam) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("id", String.valueOf(jobId));
        params.add("executorParam", executorParam);
        params.add("addressList", "");
        postWithAuth("/jobinfo/trigger", params, "触发调度任务失败");
        logger.info("Triggered XXL-JOB job: jobId={}, executorParam={}", jobId, executorParam);
    }

    private MultiValueMap<String, String> buildJobParams(Integer jobId, int jobGroup, String name,
                                                         String cronExpression, String scheduleType,
                                                         boolean isNew, Long taskId, boolean start) {
        boolean cron = "CRON".equalsIgnoreCase(scheduleType);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("jobGroup", String.valueOf(jobGroup));
        params.add("jobDesc", name);
        params.add("author", DEFAULT_AUTHOR);
        params.add("scheduleType", cron ? "CRON" : "NONE");
        params.add("scheduleConf", cron && StringUtils.hasText(cronExpression) ? cronExpression : "");
        params.add("glueType", GLUE_TYPE_BEAN);
        params.add("executorHandler", HANDLER_NAME);
        params.add("executorParam", String.valueOf(taskId));
        params.add("executorRouteStrategy", ROUTE_STRATEGY);
        params.add("misfireStrategy", MISFIRE_STRATEGY);
        params.add("executorBlockStrategy", BLOCK_STRATEGY);
        params.add("executorTimeout", "0");
        params.add("executorFailRetryCount", "0");
        params.add("childJobId", "");
        params.add("triggerStatus", cron && start ? "1" : "0");
        return params;
    }

    private int ensureJobGroup() {
        JsonNode page = getWithAuth("/jobgroup/pageList?start=0&length=10&appname=" + appName, "查询执行器失败");
        List<JsonNode> groups = extractPageList(page);
        if (!groups.isEmpty()) {
            return groups.get(0).path("id").asInt();
        }

        logger.warn("XXL-JOB job group not found, creating: appName={}", appName);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("appname", appName);
        params.add("title", appName);
        params.add("addressType", "0");
        postWithAuth("/jobgroup/insert", params, "创建执行器失败");

        JsonNode reloaded = getWithAuth("/jobgroup/pageList?start=0&length=10&appname=" + appName, "查询执行器失败");
        List<JsonNode> reloadedGroups = extractPageList(reloaded);
        if (reloadedGroups.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "无法获取 XXL-JOB 执行器分组");
        }
        return reloadedGroups.get(0).path("id").asInt();
    }

    private List<JsonNode> extractPageList(JsonNode response) {
        JsonNode data = response.path("data").path("data");
        if (data.isArray()) {
            int size = data.size();
            List<JsonNode> list = new java.util.ArrayList<>(size);
            for (JsonNode node : data) {
                list.add(node);
            }
            return list;
        }
        return Collections.emptyList();
    }

    private synchronized void login() {
        try {
            MultiValueMap<String, String> loginParams = new LinkedMultiValueMap<>();
            loginParams.add("userName", adminUsername);
            loginParams.add("password", adminPassword);
            loginParams.add("ifRemember", "on");

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.http.HttpEntity<MultiValueMap<String, String>> request =
                    new org.springframework.http.HttpEntity<>(loginParams, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    adminAddresses + "/auth/doLogin", request, String.class);

            String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
            if (cookie == null) {
                throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "XXL-JOB 登录响应未返回 Cookie");
            }
            sessionCookie = cookie;
            logger.info("Logged in to XXL-JOB admin, cookie received");
        } catch (RestClientResponseException e) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "XXL-JOB 登录失败: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "XXL-JOB 登录失败: " + e.getMessage());
        }
    }

    private JsonNode postWithAuth(String path, MultiValueMap<String, String> params, String errorMessage) {
        return exchangeWithAuth(path, true, params, errorMessage);
    }

    private JsonNode getWithAuth(String path, String errorMessage) {
        return exchangeWithAuth(path, false, null, errorMessage);
    }

    private JsonNode exchangeWithAuth(String path, boolean post, MultiValueMap<String, String> params, String errorMessage) {
        if (sessionCookie == null) {
            login();
        }
        try {
            JsonNode result = doExchange(path, post, params);
            if (isLoginRequired(result)) {
                logger.warn("XXL-JOB session expired, re-login and retry: path={}", path);
                login();
                result = doExchange(path, post, params);
            }
            return assertSuccess(result, errorMessage);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, errorMessage + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, errorMessage + ": " + e.getMessage(), e);
        }
    }

    private JsonNode doExchange(String path, boolean post, MultiValueMap<String, String> params) throws Exception {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }

        String url = adminAddresses + path;
        org.springframework.http.HttpEntity<MultiValueMap<String, String>> request =
                new org.springframework.http.HttpEntity<>(params, headers);

        ResponseEntity<String> response;
        if (post) {
            response = restTemplate.postForEntity(url, request, String.class);
        } else {
            org.springframework.http.HttpEntity<Void> getRequest = new org.springframework.http.HttpEntity<>(headers);
            response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, getRequest, String.class);
        }
        return objectMapper.readTree(response.getBody());
    }

    private boolean isLoginRequired(JsonNode response) {
        if (response == null) {
            return true;
        }
        String msg = response.path("msg").asText("");
        return msg.contains("login") || msg.contains("Login") || msg.contains("请登录") || msg.contains("未登录");
    }

    private JsonNode assertSuccess(JsonNode response, String errorMessage) {
        if (response == null) {
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, errorMessage + ": 空响应");
        }
        int code = response.path("code").asInt(-1);
        if (code != 200) {
            String msg = response.path("msg").asText("未知错误");
            throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, errorMessage + " (code=" + code + "): " + msg);
        }
        return response;
    }

    private Integer parseJobId(JsonNode response) {
        String data = response.path("data").asText(null);
        if (data != null) {
            try {
                return Integer.parseInt(data);
            } catch (NumberFormatException ignored) {
            }
        }
        throw new BusinessException(ErrorCode.TASK_SCHEDULE_FAILED, "XXL-JOB 返回的任务 ID 无效");
    }
}
