package com.datanest.dataservice.filter;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.dataservice.entity.ApiKey;
import com.datanest.dataservice.entity.ApiKeyBinding;
import com.datanest.dataservice.entity.DataApi;
import com.datanest.dataservice.mapper.ApiKeyBindingMapper;
import com.datanest.dataservice.mapper.ApiKeyMapper;
import com.datanest.dataservice.mapper.DataApiMapper;
import com.datanest.dataservice.service.ApiCallLogWriter;
import com.datanest.dataservice.service.ApiKeyService;
import com.datanest.dataservice.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 对外数据 API 认证过滤器（Sprint 10 F3）。
 * <p>
 * 拦截 {@code /open-api/**}（网关已放行登录态，见 gateway SaTokenConfig）：
 * X-API-Key 头 → SHA-256 哈希命中启用 Key → 按路径解析 API → 校验 Key-API 绑定 → Key 级限流。
 * 失败返回 401（Key 无效/禁用/未绑定）/ 404（API 不存在）/ 429（限流，带 Retry-After）。
 * 通过后把解析结果（DataApi + ApiKey）写入 request attribute，供 OpenApiController 复用（避免重复查库）。
 * <p>
 * 仅校验路径前缀，与 {@code com.datanest.common.internal.InternalTokenFilter} 模式一致（不误伤其它路径）。
 */
@Component
public class OpenApiKeyFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/open-api/";
    private static final String API_KEY_HEADER = "X-API-Key";

    /** HTTP 429（jakarta servlet 无 SC_TOO_MANY_REQUESTS 常量） */
    private static final int SC_TOO_MANY_REQUESTS = 429;

    public static final String ATTR_API = "openApi.api";
    public static final String ATTR_KEY = "openApi.key";

    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyBindingMapper bindingMapper;
    private final DataApiMapper dataApiMapper;
    private final RateLimitService rateLimitService;
    private final ApiCallLogWriter callLogWriter;

    public OpenApiKeyFilter(ApiKeyMapper apiKeyMapper,
                            ApiKeyBindingMapper bindingMapper,
                            DataApiMapper dataApiMapper,
                            RateLimitService rateLimitService,
                            ApiCallLogWriter callLogWriter) {
        this.apiKeyMapper = apiKeyMapper;
        this.bindingMapper = bindingMapper;
        this.dataApiMapper = dataApiMapper;
        this.rateLimitService = rateLimitService;
        this.callLogWriter = callLogWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getServletPath().startsWith(PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // 1. Key 认证：X-API-Key 头 → SHA-256 → 命中且启用
        String plainKey = request.getHeader(API_KEY_HEADER);
        if (plainKey == null || plainKey.isBlank()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.API_KEY_INVALID);
            return;
        }
        ApiKey key = apiKeyMapper.selectOne(new QueryWrapper<ApiKey>()
                .eq("key_hash", ApiKeyService.sha256Hex(plainKey.trim()))
                .eq("status", ApiKey.STATUS_ENABLED));
        if (key == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.API_KEY_INVALID);
            return;
        }

        // 2. 按路径解析 API（path 存完整 /open-api/v1/{段}，servlet path 即完整路径）
        DataApi api = dataApiMapper.selectOne(new QueryWrapper<DataApi>()
                .eq("path", request.getServletPath()).eq("deleted", 0));
        if (api == null) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, ErrorCode.API_NOT_FOUND);
            return;
        }

        // 3. Key-API 绑定校验（未绑定 → 401）
        Long bound = bindingMapper.selectCount(new QueryWrapper<ApiKeyBinding>()
                .eq("key_id", key.getId()).eq("api_id", api.getId()));
        if (bound == null || bound == 0) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.API_KEY_INVALID);
            return;
        }

        // 4. Key 级限流（超限 → 429 + Retry-After，并记调用统计）
        int qpsLimit = key.getQpsLimit() == null ? 0 : key.getQpsLimit();
        if (!rateLimitService.tryAcquire(key.getId(), qpsLimit)) {
            callLogWriter.write(api.getId(), key.getId(), SC_TOO_MANY_REQUESTS, null);
            response.setHeader("Retry-After", String.valueOf(rateLimitService.windowSeconds()));
            writeError(response, SC_TOO_MANY_REQUESTS, ErrorCode.API_RATE_LIMITED);
            return;
        }

        // 5. 放行，传递上下文（API 状态校验/熔断/执行由 Controller 完成）
        request.setAttribute(ATTR_API, api);
        request.setAttribute(ATTR_KEY, key);
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int httpStatus, ErrorCode errorCode) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.toJSONString(Result.fail(errorCode.getCode(), errorCode.getMessage())));
    }
}
