package com.datanest.common.internal;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Feign 统一错误解码器。
 * <p>
 * 优先从响应体解析项目 {@code Result} 信封提取远端 message；
 * HTTP 503 转为 {@link RetryableException} 以触发重试，其它错误统一包装为
 * {@link BusinessException}（含服务名与路径），避免各调用方自行解析。
 * <p>
 * 仅当 classpath 存在 Feign 时装配，与 {@link InternalTokenFeignInterceptor} 同样的装配方式。
 */
@Component
@ConditionalOnClass(name = "feign.codec.ErrorDecoder")
public class InternalFeignErrorDecoder implements ErrorDecoder {

    private static final Logger logger = LoggerFactory.getLogger(InternalFeignErrorDecoder.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();
    /** 响应体摘录最大长度，避免错误消息被超大响应体撑爆 */
    private static final int BODY_SNIPPET_LIMIT = 200;

    @Override
    public Exception decode(String methodKey, Response response) {
        Request request = response.request();
        String remoteMessage = extractRemoteMessage(response);
        // 503 服务不可用视为可重试错误，交给 Feign Retryer 重试
        if (response.status() == 503 && request != null) {
            return new RetryableException(response.status(), remoteMessage, request.httpMethod(),
                    (Long) null, request);
        }
        String target = describeTarget(request);
        logger.warn("Feign 调用返回错误: methodKey={}, status={}, target={}, message={}",
                methodKey, response.status(), target, remoteMessage);
        return new BusinessException(ErrorCode.INTERNAL_ERROR,
                "远程调用失败[" + target + "]: " + remoteMessage);
    }

    /** 从 feign Target 取服务名 + 请求路径，格式如 "data-nest-alert /alert/internal/fired" */
    private String describeTarget(Request request) {
        if (request == null) {
            return "unknown";
        }
        String service = request.requestTemplate() != null && request.requestTemplate().feignTarget() != null
                ? request.requestTemplate().feignTarget().name() : "unknown";
        String path = request.requestTemplate() != null ? request.requestTemplate().path() : request.url();
        return service + " " + path;
    }

    /** 尝试按 Result 信封解析远端 message；非信封/解析失败则取响应体摘录或 reason */
    private String extractRemoteMessage(Response response) {
        String body = readBody(response);
        if (StringUtils.hasText(body)) {
            try {
                JsonNode node = JSON_MAPPER.readTree(body);
                JsonNode message = node.get("message");
                if (message != null && message.isString() && StringUtils.hasText(message.asString())) {
                    return message.asString();
                }
            } catch (Exception ignored) {
                // 非 JSON 信封（如网关 HTML 错误页），退化为响应体摘录
            }
            return body.length() <= BODY_SNIPPET_LIMIT ? body : body.substring(0, BODY_SNIPPET_LIMIT);
        }
        return StringUtils.hasText(response.reason()) ? response.reason() : "HTTP " + response.status();
    }

    private String readBody(Response response) {
        if (response.body() == null) {
            return null;
        }
        try (Reader reader = response.body().asReader(StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
