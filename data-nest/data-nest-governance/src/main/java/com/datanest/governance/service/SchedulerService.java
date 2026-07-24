package com.datanest.governance.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxl.job.core.constant.ExecutorBlockStrategyEnum;
import com.xxl.job.core.glue.GlueTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class SchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String adminBaseUrl;
    private final String username;
    private final String password;
    private final int jobGroupId;

    private volatile String sessionCookie;

    public SchedulerService(@Value("${xxl.job.admin.addresses}") String adminBaseUrl,
                            @Value("${datanest.governance.xxl-job.username:admin}") String username,
                            @Value("${datanest.governance.xxl-job.password:123456}") String password,
                            @Value("${datanest.governance.xxl-job.job-group-id:1}") int jobGroupId) {
        this.restTemplate = new RestTemplate();
        this.adminBaseUrl = adminBaseUrl;
        this.username = username;
        this.password = password;
        this.jobGroupId = jobGroupId;
    }

    public Integer registerJob(String jobDesc, String cron, String scheduleType) {
        ensureLogin();
        MultiValueMap<String, String> params = jobBaseParams();
        params.add("jobDesc", jobDesc);
        params.add("author", "governance");
        params.add("scheduleType", scheduleType);
        params.add("scheduleConf", cron);
        params.add("glueType", GlueTypeEnum.BEAN.name());
        params.add("executorHandler", "collectTaskHandler");
        params.add("executorRouteStrategy", "FIRST");
        params.add("misfireStrategy", "DO_NOTHING");
        params.add("executorBlockStrategy", ExecutorBlockStrategyEnum.SERIAL_EXECUTION.name());
        params.add("executorTimeout", "0");
        params.add("executorFailRetryCount", "0");
        params.add("triggerStatus", scheduleType.equals("CRON") ? "1" : "0");
        params.add("triggerLastTime", "0");
        params.add("triggerNextTime", "0");

        JsonNode response = postWithRetry("/jobinfo/add", params);
        if (response == null || response.get("code") == null || response.get("code").asInt() != 200) {
            String msg = response == null ? "empty response" : response.path("msg").asText("unknown");
            throw new IllegalStateException("XXL-JOB 注册任务失败: " + msg);
        }
        return response.path("content").asInt();
    }

    public void updateJob(Integer xxlJobId, String jobDesc, String cron, String scheduleType) {
        ensureLogin();
        MultiValueMap<String, String> params = jobBaseParams();
        params.add("id", String.valueOf(xxlJobId));
        params.add("jobDesc", jobDesc);
        params.add("author", "governance");
        params.add("scheduleType", scheduleType);
        params.add("scheduleConf", cron);
        params.add("glueType", GlueTypeEnum.BEAN.name());
        params.add("executorHandler", "collectTaskHandler");
        params.add("executorRouteStrategy", "FIRST");
        params.add("misfireStrategy", "DO_NOTHING");
        params.add("executorBlockStrategy", ExecutorBlockStrategyEnum.SERIAL_EXECUTION.name());
        params.add("executorTimeout", "0");
        params.add("executorFailRetryCount", "0");
        params.add("triggerStatus", scheduleType.equals("CRON") ? "1" : "0");

        JsonNode response = postWithRetry("/jobinfo/update", params);
        if (response == null || response.get("code") == null || response.get("code").asInt() != 200) {
            String msg = response == null ? "empty response" : response.path("msg").asText("unknown");
            throw new IllegalStateException("XXL-JOB 更新任务失败: " + msg);
        }
    }

    public void unregisterJob(Integer xxlJobId) {
        ensureLogin();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("id", String.valueOf(xxlJobId));
        JsonNode response = postWithRetry("/jobinfo/remove", params);
        if (response == null || response.get("code") == null || response.get("code").asInt() != 200) {
            logger.warn("XXL-JOB 注销任务可能失败: xxlJobId={}, response={}", xxlJobId, response);
        }
    }

    public void triggerJob(Integer xxlJobId, String executorParam) {
        ensureLogin();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("id", String.valueOf(xxlJobId));
        if (executorParam != null) {
            params.add("executorParam", executorParam);
        }
        JsonNode response = postWithRetry("/jobinfo/trigger", params);
        if (response == null || response.get("code") == null || response.get("code").asInt() != 200) {
            String msg = response == null ? "empty response" : response.path("msg").asText("unknown");
            throw new IllegalStateException("XXL-JOB 触发任务失败: " + msg);
        }
    }

    private MultiValueMap<String, String> jobBaseParams() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("jobGroup", String.valueOf(jobGroupId()));
        params.add("alarmEmail", "");
        params.add("executorParam", "");
        params.add("childJobId", "");
        return params;
    }

    private int jobGroupId() {
        return jobGroupId;
    }

    private JsonNode postWithRetry(String path, MultiValueMap<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (sessionCookie != null) {
            headers.put(HttpHeaders.COOKIE, List.of(sessionCookie));
        }
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(adminBaseUrl + path, request, String.class);
            return parseResponse(response.getBody());
        } catch (Exception e) {
            if (isUnauthorized(e)) {
                logger.warn("XXL-JOB session expired or invalid, re-login and retry once: path={}", path);
                login();
                headers.put(HttpHeaders.COOKIE, List.of(sessionCookie));
                HttpEntity<MultiValueMap<String, String>> retryRequest = new HttpEntity<>(params, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(adminBaseUrl + path, retryRequest, String.class);
                return parseResponse(response.getBody());
            }
            throw new IllegalStateException("XXL-JOB API 调用失败: " + path, e);
        }
    }

    private JsonNode parseResponse(String body) {
        if (body == null) {
            return null;
        }
        if (looksLikeLoginPage(body)) {
            throw new IllegalStateException("XXL-JOB 响应为登录页，会话已失效");
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("XXL-JOB 响应解析失败", e);
        }
    }

    private void ensureLogin() {
        if (sessionCookie == null) {
            login();
        }
    }

    private synchronized void login() {
        if (sessionCookie != null) {
            return;
        }
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("userName", username);
        params.add("password", password);
        params.add("remember_me", "false");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(adminBaseUrl + "/login", request, String.class);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            throw new IllegalStateException("XXL-JOB 登录失败，未返回 Cookie");
        }
        this.sessionCookie = cookies.get(0);
        logger.info("XXL-JOB login succeeded");
    }

    private boolean isUnauthorized(Exception e) {
        if (e instanceof HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            return status == HttpStatus.UNAUTHORIZED
                    || status == HttpStatus.FORBIDDEN
                    || status == HttpStatus.FOUND
                    || status == HttpStatus.MOVED_PERMANENTLY;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("401")
                || lower.contains("403")
                || lower.contains("unauthorized")
                || lower.contains("未登录")
                || lower.contains("登录")
                || lower.contains("login")
                || lower.contains("session")
                || lower.contains("cookie");
    }

    private boolean looksLikeLoginPage(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("<!doctype html")
                && (lower.contains("login") || lower.contains("xxl-sso") || lower.contains("登录") || lower.contains("用户名"));
    }
}
