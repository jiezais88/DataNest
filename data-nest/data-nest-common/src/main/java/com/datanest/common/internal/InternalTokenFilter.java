package com.datanest.common.internal;

import com.datanest.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务间内部调用令牌过滤器。
 * <p>
 * 仅校验 servlet path 以 {@code /internal/} 开头的请求（服务间 Feign 内部端点），
 * 不会误伤 {@code /dev/internal/**} 之类的 DolphinScheduler 回调路径。
 * 请求头 {@code X-Internal-Token} 需与配置 {@code datanest.internal.token} 一致，
 * 不一致返回 401 + Result 错误信封；未配置时放行（本地开发兜底，只告警一次）。
 * <p>
 * 仅在 Servlet 应用中装配，避免 WebFlux 的 gateway 误装配。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 内部调用令牌请求头 */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /** 内部调用路径前缀（注意必须是 /internal/ 开头） */
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    /** 内部调用令牌，空表示关闭校验（本地开发兜底） */
    @Value("${datanest.internal.token:}")
    private String internalToken;

    /** 令牌未配置时的告警日志只打一次 */
    private final AtomicBoolean warnLogged = new AtomicBoolean(false);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 仅拦截内部调用路径，其余请求直接放行
        if (!request.getServletPath().startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 未配置令牌时放行（本地开发兜底），只打一次告警日志
        if (!StringUtils.hasText(internalToken)) {
            if (warnLogged.compareAndSet(false, true)) {
                log.warn("datanest.internal.token 未配置，内部调用接口不做令牌校验，请仅用于本地开发环境");
            }
            filterChain.doFilter(request, response);
            return;
        }
        if (internalToken.equals(request.getHeader(INTERNAL_TOKEN_HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }
        // 令牌不匹配，返回 401 + Result 错误信封
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + ErrorCode.UNAUTHORIZED.getCode()
                + ",\"message\":\"内部调用令牌无效\",\"data\":null}");
    }
}
