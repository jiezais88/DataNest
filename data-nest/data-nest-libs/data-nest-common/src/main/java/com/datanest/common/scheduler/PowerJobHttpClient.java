package com.datanest.common.scheduler;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;

/**
 * PowerJob OpenAPI 内部 HTTP 边界。
 * <p>
 * 仅负责 RestClient 创建、超时、POST 请求和 HTTP 层异常转换；PowerJob
 * ResultDTO 的 success/message 语义由上层业务客户端处理。
 */
final class PowerJobHttpClient {

    private final RestClient restClient;

    private PowerJobHttpClient(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    static PowerJobHttpClient scheduler() {
        return new PowerJobHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    static PowerJobHttpClient appBootstrap() {
        return new PowerJobHttpClient(Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    String postQuery(URI uri, String errorMessage) {
        try {
            return restClient.post()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    errorMessage + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    errorMessage + ": " + messageOf(e));
        }
    }

    String postJson(URI uri, String json, String errorMessage) {
        return postJson(uri, json, null, null, errorMessage);
    }

    String postJson(URI uri, String json, String headerName, String headerValue, String errorMessage) {
        try {
            var request = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON);
            if (headerName != null && headerValue != null) {
                request.header(headerName, headerValue);
            }
            return request
                    .body(json)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    errorMessage + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    errorMessage + ": " + messageOf(e));
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
