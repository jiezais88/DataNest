package com.datanest.common.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PowerJob App 自举注册（新环境免手工）。
 * <p>
 * PowerJob 要求 App（如 data-nest-job / data-nest-worker）在 server 端预注册，否则 worker 接入
 * 与 OpenAPI 调用都会 INVALID_APP。本组件在引用方启动时幂等确保 App 存在：
 * 先 /openApi/assert 探测，不存在则走控制台管理员账号（/auth/thirdPartyLoginDirect 拿 JWT）
 * 调 /appInfo/save 创建。任何失败只 warn 不阻塞启动（server 未就绪时下次重启自愈）。
 */
@Component
public class PowerJobAppBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(PowerJobAppBootstrap.class);

    /** server 初始化时自动创建的默认命名空间 id（SystemInitializeServiceImpl） */
    private static final long DEFAULT_NAMESPACE_ID = 2L;

    @Value("${datanest.powerjob.server-address:http://middleware-powerjob:7700}")
    private String serverAddress;
    @Value("${datanest.powerjob.app-password:powerjob123}")
    private String appPassword;
    @Value("${datanest.powerjob.admin-username:ADMIN}")
    private String adminUsername;
    @Value("${datanest.powerjob.admin-password:powerjob_admin}")
    private String adminPassword;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PowerJobAppBootstrap() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 幂等确保 App 存在。已存在（含密码一致）直接返回；不存在则经管理员账号创建。
     * 失败仅告警不抛异常。
     */
    public void ensureApp(String appName) {
        try {
            if (assertApp(appName)) {
                return;
            }
            logger.warn("[PowerJobAppBootstrap] App 不存在或密码不匹配，尝试自举注册: appName={}", appName);
            String jwt = loginAdmin();
            createApp(jwt, appName);
            if (assertApp(appName)) {
                logger.info("[PowerJobAppBootstrap] App 自举注册成功: appName={}", appName);
            } else {
                logger.warn("[PowerJobAppBootstrap] App 注册后 assert 仍未通过: appName={}（worker 接入可能失败，下次重启自愈）", appName);
            }
        } catch (Exception e) {
            logger.warn("[PowerJobAppBootstrap] App 自举注册失败（不阻塞启动）: appName={}: {}", appName, e.getMessage());
        }
    }

    /** /openApi/assert：appName+password 校验通过返回 true。 */
    private boolean assertApp(String appName) {
        try {
            String url = serverAddress + "/openApi/assert?appName=" + appName + "&password=" + appPassword;
            JsonNode result = objectMapper.readTree(restTemplate.postForEntity(url, null, String.class).getBody());
            return result.path("success").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 控制台管理员登录（PWJB 账号体系），返回 JWT。 */
    private String loginAdmin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loginType", "PWJB");
        body.put("originParams", "{\"username\":\"" + adminUsername + "\",\"password\":\"" + adminPassword + "\"}");
        JsonNode result = postJson("/auth/thirdPartyLoginDirect", body, null);
        String jwt = result.path("data").path("jwtToken").asText(null);
        if (jwt == null || jwt.isBlank()) {
            throw new IllegalStateException("管理员登录未返回 jwtToken");
        }
        return jwt;
    }

    /** /appInfo/save 创建 App（header PowerJwt 鉴权）。 */
    private void createApp(String jwt, String appName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", appName);
        body.put("password", appPassword);
        body.put("namespaceId", DEFAULT_NAMESPACE_ID);
        postJson("/appInfo/save", body, jwt);
    }

    private JsonNode postJson(String path, Map<String, Object> body, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.set("PowerJwt", jwt);
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            String resp = restTemplate.postForEntity(serverAddress + path, new HttpEntity<>(json, headers), String.class).getBody();
            JsonNode result = objectMapper.readTree(resp);
            if (!result.path("success").asBoolean(false)) {
                throw new IllegalStateException(path + " 返回失败: " + result.path("message").asText(""));
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(path + " 调用失败: " + e.getMessage(), e);
        }
    }
}
