package com.datanest.common.scheduler;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

/**
 * XXL-JOB admin 通用调度客户端，封装登录、任务注册/更新/触发/启停/注销。
 * 可被 engineering 与 governance 服务共用。
 */
@Component
public class SchedulerClient {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerClient.class);
    private static final String DEFAULT_AUTHOR = "data-nest";
    private static final String GLUE_TYPE_BEAN = "BEAN";
    private static final String ROUTE_STRATEGY = "ROUND";
    private static final String MISFIRE_STRATEGY = "DO_NOTHING";
    private static final String BLOCK_STRATEGY = "SERIAL_EXECUTION";

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.admin.username:admin}")
    private String adminUsername;

    @Value("${xxl.job.admin.password:123456}")
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

    public Integer registerJob(String appName, String executorHandler, Long jobId, String name,
                               String cron, String triggerType, boolean scheduleEnabled,
                               int timeout, int failRetryCount) {
        int jobGroup = ensureJobGroup(appName);
        MultiValueMap<String, String> params = buildJobParams(null, jobGroup, executorHandler, jobId, name,
                cron, triggerType, true, scheduleEnabled, timeout, failRetryCount);
        JsonNode response = postWithAuth("/jobinfo/insert", params, "注册调度任务失败");
        Integer xxlJobId = parseJobId(response);
        logger.info("Registered XXL-JOB job: name={}, jobGroup={}, jobId={}, businessId={}, start={}",
                name, jobGroup, xxlJobId, jobId, scheduleEnabled);
        return xxlJobId;
    }

    public void updateJob(Integer xxlJobId, String appName, String executorHandler, Long jobId, String name,
                          String cron, String triggerType, boolean scheduleEnabled,
                          int timeout, int failRetryCount) {
        int jobGroup = ensureJobGroup(appName);
        MultiValueMap<String, String> params = buildJobParams(xxlJobId, jobGroup, executorHandler, jobId, name,
                cron, triggerType, false, scheduleEnabled, timeout, failRetryCount);
        params.add("id", String.valueOf(xxlJobId));
        postWithAuth("/jobinfo/update", params, "更新调度任务失败");
        logger.info("Updated XXL-JOB job: jobId={}, name={}, businessId={}, start={}", xxlJobId, name, jobId, scheduleEnabled);
    }

    public void unregisterJob(Integer xxlJobId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("ids[]", String.valueOf(xxlJobId));
        postWithAuth("/jobinfo/delete", params, "删除调度任务失败");
        logger.info("Removed XXL-JOB job: jobId={}", xxlJobId);
    }

    public void triggerJob(Integer xxlJobId, String executorParam) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("id", String.valueOf(xxlJobId));
        params.add("executorParam", executorParam == null ? "" : executorParam);
        params.add("addressList", "");
        postWithAuth("/jobinfo/trigger", params, "触发调度任务失败");
        logger.info("Triggered XXL-JOB job: jobId={}, executorParam={}", xxlJobId, executorParam);
    }

    public void startJob(Integer xxlJobId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("ids[]", String.valueOf(xxlJobId));
        postWithAuth("/jobinfo/start", params, "启动调度任务失败");
        logger.info("Started XXL-JOB job: jobId={}", xxlJobId);
    }

    public void stopJob(Integer xxlJobId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("ids[]", String.valueOf(xxlJobId));
        postWithAuth("/jobinfo/stop", params, "停止调度任务失败");
        logger.info("Stopped XXL-JOB job: jobId={}", xxlJobId);
    }

    public MultiValueMap<String, String> buildJobInfo(String appName, String executorHandler, Long jobId, String name,
                                                      String cron, String triggerType, boolean scheduleEnabled,
                                                      int timeout, int failRetryCount) {
        int jobGroup = ensureJobGroup(appName);
        return buildJobParams(null, jobGroup, executorHandler, jobId, name, cron, triggerType, true,
                scheduleEnabled, timeout, failRetryCount);
    }

    private MultiValueMap<String, String> buildJobParams(Integer xxlJobId, int jobGroup, String executorHandler,
                                                         Long jobId, String name, String cron, String triggerType,
                                                         boolean isNew, boolean scheduleEnabled,
                                                         int timeout, int failRetryCount) {
        boolean isCron = "CRON".equalsIgnoreCase(triggerType);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("jobGroup", String.valueOf(jobGroup));
        params.add("jobDesc", name);
        params.add("author", DEFAULT_AUTHOR);
        params.add("scheduleType", isCron ? "CRON" : "NONE");
        params.add("scheduleConf", isCron && StringUtils.hasText(cron) ? cron : "");
        params.add("glueType", GLUE_TYPE_BEAN);
        params.add("executorHandler", executorHandler);
        params.add("executorParam", jobId == null ? "" : String.valueOf(jobId));
        params.add("executorRouteStrategy", ROUTE_STRATEGY);
        params.add("misfireStrategy", MISFIRE_STRATEGY);
        params.add("executorBlockStrategy", BLOCK_STRATEGY);
        params.add("executorTimeout", String.valueOf(Math.max(0, timeout)));
        params.add("executorFailRetryCount", String.valueOf(Math.max(0, failRetryCount)));
        params.add("childJobId", "");
        params.add("triggerStatus", isCron && scheduleEnabled ? "1" : "0");
        return params;
    }

    private int ensureJobGroup(String appName) {
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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法获取 XXL-JOB 执行器分组");
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
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "XXL-JOB 登录响应未返回 Cookie");
            }
            sessionCookie = cookie;
            logger.info("Logged in to XXL-JOB admin, cookie received");
        } catch (RestClientResponseException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "XXL-JOB 登录失败: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "XXL-JOB 登录失败: " + e.getMessage());
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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": " + e.getMessage(), e);
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
            response = restTemplate.exchange(url, HttpMethod.GET, getRequest, String.class);
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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + ": 空响应");
        }
        int code = response.path("code").asInt(-1);
        if (code != 200) {
            String msg = response.path("msg").asText("未知错误");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage + " (code=" + code + "): " + msg);
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
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "XXL-JOB 返回的任务 ID 无效");
    }
}
