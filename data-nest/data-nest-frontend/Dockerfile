# stable-alpine 内置 nginx/1.30.x（mainline 的 alpine 是 1.31.3），
# 与 Alpine 仓库 nginx-mod-http-brotli 的编译版本匹配，否则 load_module 报 not binary compatible。
FROM nginx:stable-alpine

# 安装 brotli 动态模块（ngx_http_brotli_*），用于 brotli_static 直接吐构建产物的 .br 预压缩文件
RUN apk add --no-cache nginx-mod-http-brotli

COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
