package com.datanest.system.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.system.config.SsoProperties;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC 授权码客户端（Sprint 14，决策 D8 自研）。
 * <p>
 * 流程：构建授权 URL（state 入内存缓存防 CSRF）→ IdP 302 回调带 code →
 * token 端点换 id_token → 通过 JWKS 公钥验签（RS256）+ 校验 iss/aud/exp →
 * 提取 sub/email/name/groups。
 * 支持端点显式配置；授权/令牌/JWKS 端点任一为空时走 OIDC Discovery。
 */
@Service
public class OidcClientService {

    private static final Logger log = LoggerFactory.getLogger(OidcClientService.class);
    private static final long STATE_TTL_MILLIS = 10 * 60 * 1000L;

    private final SsoConfigService ssoConfigService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    /** state 缓存：state -> 过期时间戳（登录态外可用内存 Map，system 单实例） */
    private final Map<String, Long> stateStore = new ConcurrentHashMap<>();
    /** Discovery 缓存：issuer -> endpoints */
    private final Map<String, OidcDiscovery> discoveryCache = new ConcurrentHashMap<>();

    public OidcClientService(SsoConfigService ssoConfigService) {
        this.ssoConfigService = ssoConfigService;
    }

    /** 构建 OIDC 授权 URL（前端跳转到 IdP） */
    public String buildAuthorizationUrl() {
        SsoProperties.Oidc oidc = requireOidc();
        OidcDiscovery d = discovery(oidc);
        String state = UUID.randomUUID().toString().replace("-", "");
        stateStore.put(state, System.currentTimeMillis() + STATE_TTL_MILLIS);
        String authEp = blank(oidc.getAuthorizationEndpoint())
                ? d.authorizationEndpoint() : oidc.getAuthorizationEndpoint();
        return authEp
                + "?response_type=code"
                + "&client_id=" + enc(oidc.getClientId())
                + "&redirect_uri=" + enc(oidc.getRedirectUri())
                + "&scope=" + enc(oidc.getScope() == null || oidc.getScope().isBlank()
                        ? "openid,profile,email" : oidc.getScope())
                + "&state=" + state;
    }

    /** 用授权码换取并校验 id_token，返回用户信息 */
    public OidcUserInfo authenticate(String code, String state) {
        Long expireAt = stateStore.remove(state);
        if (expireAt == null || expireAt < System.currentTimeMillis()) {
            throw new BusinessException(ErrorCode.SSO_STATE_INVALID);
        }
        SsoProperties.Oidc oidc = requireOidc();
        OidcDiscovery d = discovery(oidc);
        String tokenEp = blank(oidc.getTokenEndpoint()) ? d.tokenEndpoint() : oidc.getTokenEndpoint();
        try {
            String body = postForm(tokenEp, Map.of(
                    "grant_type", "authorization_code",
                    "code", code,
                    "redirect_uri", oidc.getRedirectUri(),
                    "client_id", oidc.getClientId(),
                    "client_secret", oidc.getClientSecret() == null ? "" : oidc.getClientSecret()
            ));
            JSONObject tokenJson = JSON.parseObject(body);
            String idToken = tokenJson == null ? null : tokenJson.getString("id_token");
            if (idToken == null || idToken.isBlank()) {
                log.warn("OIDC token 端点未返回 id_token: {}", body);
                throw new BusinessException(ErrorCode.SSO_ID_TOKEN_INVALID);
            }
            return verifyIdToken(oidc, d, idToken);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OIDC 授权码换取令牌失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SSO_ID_TOKEN_INVALID);
        }
    }

    private OidcUserInfo verifyIdToken(SsoProperties.Oidc oidc, OidcDiscovery d, String idToken) throws Exception {
        SignedJWT jwt = SignedJWT.parse(idToken);
        String jwksUri = blank(oidc.getJwksUri()) ? d.jwksUri() : oidc.getJwksUri();
        JWKSet jwkSet = JWKSet.parse(get(jwksUri));
        JWK jwk = jwt.getHeader().getKeyID() != null ? jwkSet.getKeyByKeyId(jwt.getHeader().getKeyID()) : null;
        if (jwk == null) {
            for (JWK k : jwkSet.getKeys()) {
                if (k instanceof RSAKey) {
                    jwk = k;
                    break;
                }
            }
        }
        if (!(jwk instanceof RSAKey rsaKey)) {
            throw new BusinessException(ErrorCode.SSO_ID_TOKEN_INVALID);
        }
        if (!jwt.verify(new RSASSAVerifier(rsaKey))) {
            throw new BusinessException(ErrorCode.SSO_ID_TOKEN_INVALID);
        }
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        String issuer = claims.getIssuer();
        if (issuer == null || (oidc.getIssuer() != null && !oidc.getIssuer().isBlank()
                && !issuer.equals(oidc.getIssuer().replaceAll("/$", "")))) {
            throw new BusinessException(ErrorCode.SSO_ID_TOKEN_INVALID);
        }
        if (!claims.getAudience().contains(oidc.getClientId())) {
            throw new BusinessException(ErrorCode.SSO_ID_TOKEN_INVALID);
        }
        Date exp = claims.getExpirationTime();
        if (exp == null || exp.before(new Date())) {
            throw new BusinessException(ErrorCode.SSO_ID_TOKEN_INVALID);
        }
        // 平台用户名优先 preferred_username，其次 email 前缀（resolveSsoUser 兜底），name 仅作显示名
        String preferredUsername = null;
        try {
            Object u = claims.getClaim("preferred_username");
            if (u != null) {
                preferredUsername = String.valueOf(u);
            }
        } catch (Exception ignored) {
            // claim 缺失按无处理
        }
        return new OidcUserInfo(claims.getSubject(),
                claims.getStringClaim("email"),
                preferredUsername,
                claims.getStringClaim("name"),
                extractGroups(claims));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGroups(JWTClaimsSet claims) {
        Object g = null;
        try {
            g = claims.getClaim("groups");
        } catch (Exception ignored) {
            // claim 缺失按无组处理
        }
        if (g instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (g instanceof String s && !s.isBlank()) {
            List<String> groups = new ArrayList<>();
            for (String part : s.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    groups.add(t);
                }
            }
            return groups;
        }
        return List.of();
    }

    private OidcDiscovery discovery(SsoProperties.Oidc oidc) {
        boolean explicit = !blank(oidc.getAuthorizationEndpoint())
                && !blank(oidc.getTokenEndpoint()) && !blank(oidc.getJwksUri());
        if (explicit) {
            return new OidcDiscovery(oidc.getIssuer(), oidc.getAuthorizationEndpoint(),
                    oidc.getTokenEndpoint(), oidc.getJwksUri());
        }
        if (blank(oidc.getIssuer())) {
            throw new BusinessException(ErrorCode.SSO_NOT_CONFIGURED);
        }
        return discoveryCache.computeIfAbsent(oidc.getIssuer(), issuer -> {
            try {
                String url = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
                JSONObject json = JSON.parseObject(get(url));
                if (json == null) {
                    throw new BusinessException(ErrorCode.SSO_NOT_CONFIGURED);
                }
                return new OidcDiscovery(json.getString("issuer"),
                        json.getString("authorization_endpoint"),
                        json.getString("token_endpoint"),
                        json.getString("jwks_uri"));
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.warn("OIDC Discovery 拉取失败 issuer={}: {}", oidc.getIssuer(), e.getMessage());
                throw new BusinessException(ErrorCode.SSO_NOT_CONFIGURED);
            }
        });
    }

    private SsoProperties.Oidc requireOidc() {
        SsoProperties props = ssoConfigService.getSsoProperties();
        if (!props.isEnabled() || props.getOidc() == null || !props.getOidc().isEnabled()) {
            throw new BusinessException(ErrorCode.SSO_NOT_CONFIGURED);
        }
        return props.getOidc();
    }

    // ---------- HTTP helpers（JDK HttpClient + fastjson2） ----------
    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private String postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private record OidcDiscovery(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

    public record OidcUserInfo(String subject, String email, String username, String name, List<String> groups) {}
}
