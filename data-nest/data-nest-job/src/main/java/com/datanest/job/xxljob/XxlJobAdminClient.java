package com.datanest.job.xxljob;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

/**
 * XXL-JOB Admin REST 客户端，供 JobRegistrar 注册/更新平台定时任务。
 */
@Component
public class XxlJobAdminClient {

    private static final Logger logger = LoggerFactory.getLogger(XxlJobAdminClient.class);
    private static final String DEFAULT_AUTHOR = "data-nest-job";

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.admin.username:admin}")
    private String adminUsername;

    @Value("${xxl.job.admin.password:123456}")
    private String adminPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private String sessionCookie;

    public XxlJobAdminClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 查询或创建执行器分组，返回 jobGroup ID。
     */
    public int ensureJobGroup(String appName) {
        JsonNode page = getWithAuth("/jobgroup/pageList?offset=0&pagesize=10&appname=" + appName, "查询执行器失败");
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

        JsonNode reloaded = getWithAuth("/jobgroup/pageList?offset=0&pagesize=10&appname=" + appName, "查询执行器失败");
        List<JsonNode> reloadedGroups = extractPageList(reloaded);
        if (reloadedGroups.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法获取 XXL-JOB 执行器分组");
        }
        return reloadedGroups.get(0).path("id").asInt();
    }

    /**
     * 根据执行器分组与 handler 名称查询已存在的任务。
     */
    public JsonNode findJobByHandler(int jobGroup, String executorHandler) {
        JsonNode page = getWithAuth("/jobinfo/pageList?jobGroup=" + jobGroup + "&triggerStatus=-1&jobDesc=&executorHandler=" + executorHandler + "&author=&offset=0&pagesize=100", "查询任务列表失败");
        List<JsonNode> jobs = extractPageList(page);
        for (JsonNode job : jobs) {
            if (executorHandler.equals(job.path("executorHandler").asText())) {
                return job;
            }
        }
        return null;
    }

    /**
     * 新增任务，返回任务 ID。
     */
    public Integer addJob(MultiValueMap<String, String> params) {
        JsonNode response = postWithAuth("/jobinfo/insert", params, "新增任务失败");
        return parseJobId(response);
    }

    /**
     * 更新任务。
     */
    public void updateJob(MultiValueMap<String, String> params) {
        postWithAuth("/jobinfo/update", params, "更新任务失败");
    }

    public MultiValueMap<String, String> buildJobParams(Integer jobId, int jobGroup, String executorHandler,
                                                        String jobDesc, String cron, String scheduleType,
                                                        boolean triggerStatus) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (jobId != null) {
            params.add("id", String.valueOf(jobId));
        }
        params.add("jobGroup", String.valueOf(jobGroup));
        params.add("jobDesc", jobDesc);
        params.add("author", DEFAULT_AUTHOR);
        params.add("scheduleType", scheduleType);
        params.add("scheduleConf", cron);
        params.add("glueType", "BEAN");
        params.add("executorHandler", executorHandler);
        params.add("executorParam", "");
        params.add("executorRouteStrategy", "ROUND");
        params.add("misfireStrategy", "DO_NOTHING");
        params.add("executorBlockStrategy", "SERIAL_EXECUTION");
        params.add("executorTimeout", "0");
        params.add("executorFailRetryCount", "0");
        params.add("childJobId", "");
        params.add("triggerStatus", triggerStatus ? "1" : "0");
        return params;
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

            HttpHeaders headers = new HttpHeaders();
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
        HttpHeaders headers = new HttpHeaders();
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
