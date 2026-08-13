package com.datanest.dataservice.controller;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.dataservice.dto.OpenApiResult;
import com.datanest.dataservice.entity.ApiKey;
import com.datanest.dataservice.entity.DataApi;
import com.datanest.dataservice.filter.OpenApiKeyFilter;
import com.datanest.dataservice.service.OpenApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外数据 API 执行入口（Sprint 10 F3）。
 * <p>
 * {@code GET /open-api/v1/{自定义path}}：网关已放行登录态，OpenApiKeyFilter 完成 X-API-Key 认证 +
 * 绑定校验 + Key 级限流（401/404/429 在 filter 直接返回）。此处完成状态校验（未发布 404）、
 * 熔断（503）、执行（200）。对外用 HTTP 状态码语义（区别于管理端 Result 信封 200）。
 */
@Tag(name = "对外数据 API", description = "业务系统凭 X-API-Key 调用已发布 API（Sprint 10 F3）")
@RestController
@RequestMapping("/open-api/v1")
public class OpenApiController {

    private final OpenApiService openApiService;

    public OpenApiController(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    @Operation(summary = "调用数据 API", description = "X-API-Key 认证 + 限流 + 熔断 + 参数化查询；未发布 404 / 熔断 503 / 超限 429")
    @GetMapping("/{segment}")
    public ResponseEntity<Result<OpenApiResult>> execute(@PathVariable("segment") String segment,
                                                         HttpServletRequest request) {
        DataApi api = (DataApi) request.getAttribute(OpenApiKeyFilter.ATTR_API);
        ApiKey key = (ApiKey) request.getAttribute(OpenApiKeyFilter.ATTR_KEY);
        Map<String, String> queryParams = toQueryParams(request);
        try {
            OpenApiResult result = openApiService.execute(api, key, queryParams);
            return ResponseEntity.ok(Result.ok(result));
        } catch (BusinessException e) {
            return ResponseEntity.status(mapStatus(e.getErrorCode()))
                    .body(Result.fail(e.getErrorCode().getCode(), e.getMessage()));
        }
    }

    /** 业务错误码 → 对外 HTTP 状态码（未发布 404 / 熔断 503 / 其它 500） */
    private HttpStatus mapStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case API_NOT_PUBLISHED -> HttpStatus.NOT_FOUND;
            case API_CIRCUIT_OPEN -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** query 参数取第一个值（对外 API 参数化筛选值） */
    private Map<String, String> toQueryParams(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) {
                params.put(k, v[0]);
            }
        });
        return params;
    }
}
