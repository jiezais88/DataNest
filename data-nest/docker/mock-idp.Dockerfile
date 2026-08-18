# ============================================
# Mock OIDC IdP（Sprint 14 测试工具，非生产代码）
# 标准 OIDC 授权码流程 + RS256 签名，供 OIDC 登录 E2E 自测
# issuer=http://host.docker.internal:9040：
#   - system 容器经 host.docker.internal → 宿主 9040 → 本容器（端口映射回环）
#   - 宿主浏览器/curl 经 localhost:9040 直接访问（端口映射）
# 测试用户：alice(组 datanest-engineers) / bob(组 datanest-admins) / carol(无组)
# ============================================
FROM node:24-alpine
WORKDIR /app
COPY scripts/sso-mock-idp/server.js /app/server.js
EXPOSE 9040
ENTRYPOINT ["node", "/app/server.js", "9040", "http://host.docker.internal:9040"]
