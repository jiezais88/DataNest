# ============================================================
# Flink 2.2.1 Session 集群（Sprint 8 实时 CDC）
# 基于官方镜像，预置 Flink CDC 3.6.0-2.2 全部运行时依赖 + S3/MinIO 访问配置
# 依赖版本矩阵（M0 实测锁定）：
#   - flink-cdc-pipeline-connector-mysql/iceberg: 3.6.0-2.2（shaded 自包含）
#   - flink-cdc-dist / flink-cdc-common / flink-cdc-flink2-compat: 3.6.0-2.2
#   - mysql-connector-j: 8.0.33（CDC 文档推荐 8.0.27 已从 central 下架）
#   - flink-shaded-hadoop-2-uber: 2.8.3-10.0（Hadoop FileSystem，S3A 依赖）
#   - flink-s3-fs-hadoop: 2.2.1（S3AFileSystem 实现，lib 内供作业可见；勿同时放 plugins/ 会重复注册）
# ============================================================
FROM flink:2.2.1-scala_2.12-java21

# 预置 CDC 相关依赖到 lib（对作业 classloader 可见）
COPY lib/*.jar /opt/flink/lib/

# MinIO S3A 配置（core-site.xml），S3AFileSystem 启动时自动从 HADOOP_CONF_DIR 加载
COPY core-site.xml /opt/flink/conf/core-site.xml
ENV HADOOP_CONF_DIR=/opt/flink/conf

# 追加 MinIO S3 访问配置（flink-s3-fs-hadoop 映射为 fs.s3a.*）+ 类加载顺序
# classloader.resolve-order=parent-first：作业类优先从集群 lib 解析，
# 避免 connector(iceberg 等) 同时存在于 lib 与 pipeline.jars 导致双 classloader 加载冲突
RUN printf '\n# MinIO S3 (Sprint 8 realtime CDC)\ns3.access-key: datanest\ns3.secret-key: datanest123\ns3.endpoint: http://middleware-minio:9000\ns3.path.style.access: true\nclassloader.resolve-order: parent-first\n' >> /opt/flink/conf/config.yaml

# 保持官方镜像默认的 flink 用户
USER flink
