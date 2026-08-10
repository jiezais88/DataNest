package com.datanest.realtime.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.realtime.entity.CdcPipeline;
import com.datanest.realtime.entity.CdcPipelineTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CDC YAML 组装器：按管道实体 + 表映射生成 Flink CDC YAML Pipeline 定义。
 * <p>
 * 模板对齐 Sprint 8 M0 验证范本 {@code tmp/m0-cdc-verify/pipeline.yaml}：
 * MySQL source → Iceberg sink（Hadoop Catalog + S3A 直写 MinIO）+ route 表级路由。
 */
@Component
public class CdcYamlBuilder {

    /** Iceberg warehouse（s3a://datalake/warehouse） */
    @Value("${datanest.realtime.iceberg.warehouse}")
    private String icebergWarehouse;

    /** MinIO S3 endpoint（容器内网地址） */
    @Value("${datanest.minio.endpoint}")
    private String minioEndpoint;

    @Value("${datanest.minio.access-key}")
    private String minioAccessKey;

    @Value("${datanest.minio.secret-key}")
    private String minioSecretKey;

    /** 作业并行度 */
    @Value("${datanest.realtime.flink.parallelism:1}")
    private Integer parallelism;

    /**
     * 组装 CDC YAML。
     *
     * @param pipeline      管道实体
     * @param tables        表级映射（targetTable 已回填默认值）
     * @param host          源 MySQL host（容器内网地址）
     * @param port          源 MySQL 端口
     * @param username      源 MySQL 用户名
     * @param plainPassword 源 MySQL 明文密码（已解密）
     */
    public String build(CdcPipeline pipeline, List<CdcPipelineTable> tables,
                        String host, Integer port, String username, String plainPassword) {
        String startupMode = toFlinkStartupMode(pipeline);
        // server-id：同源并发管道靠管道 id 取模错开区间（每条管道占 100 个 id 区间）；
        // 区间起点 5400 + (id % 100) * 100，100 个区间内冲突概率可接受
        long serverIdBase = 5400 + (pipeline.getId() % 100) * 100L;
        String serverIdRange = serverIdBase + "-" + (serverIdBase + 99);

        String tableList = tables.stream()
                .map(t -> pipeline.getSourceDatabase() + "." + t.getSourceTable())
                .collect(Collectors.joining(","));

        StringBuilder yaml = new StringBuilder();
        yaml.append("source:\n");
        yaml.append("  type: mysql\n");
        yaml.append("  name: MySQL Source\n");
        yaml.append("  hostname: ").append(quote(host)).append('\n');
        yaml.append("  port: ").append(port).append('\n');
        yaml.append("  username: ").append(quote(username)).append('\n');
        yaml.append("  password: ").append(quote(plainPassword)).append('\n');
        yaml.append("  tables: ").append(quote(tableList)).append('\n');
        yaml.append("  server-id: '").append(serverIdRange).append("'\n");
        // caching_sha2_password + 非 SSL 需要公钥检索放行，经 jdbc.properties.* 透传
        yaml.append("  jdbc.properties.useSSL: 'false'\n");
        yaml.append("  jdbc.properties.allowPublicKeyRetrieval: 'true'\n");
        // 仅无 savepoint 恢复时生效（有 savepoint 时 Flink 从 savepoint 状态续跑，忽略启动位点）
        yaml.append("  scan.startup.mode: ").append(startupMode).append('\n');

        yaml.append("sink:\n");
        yaml.append("  type: iceberg\n");
        yaml.append("  name: Iceberg Sink\n");
        // Hadoop Catalog，warehouse 指向 MinIO；不配 io-impl：走 Hadoop FileSystem + flink-s3-fs-hadoop(S3A)
        yaml.append("  catalog.properties.type: hadoop\n");
        yaml.append("  catalog.properties.warehouse: ").append(quote(icebergWarehouse)).append('\n');
        // iceberg HadoopCatalog 只把 hadoop.* 前缀的属性透传进 Hadoop Configuration
        yaml.append("  catalog.properties.hadoop.fs.s3a.endpoint: ").append(quote(minioEndpoint)).append('\n');
        yaml.append("  catalog.properties.hadoop.fs.s3a.access.key: ").append(quote(minioAccessKey)).append('\n');
        yaml.append("  catalog.properties.hadoop.fs.s3a.secret.key: ").append(quote(minioSecretKey)).append('\n');
        yaml.append("  catalog.properties.hadoop.fs.s3a.path.style.access: true\n");
        // 注意：CDC 3.6.0-2.2 iceberg connector 不支持 upsert 选项（提交即报 Unsupported options），
        // write_mode 暂只作平台层配置与 UPSERT 主键必填校验，不下发到 YAML

        yaml.append("route:\n");
        for (CdcPipelineTable table : tables) {
            yaml.append("  - source-table: ").append(quote(pipeline.getSourceDatabase() + "." + table.getSourceTable())).append('\n');
            yaml.append("    sink-table: ").append(quote(pipeline.getTargetDatabase() + "." + table.getTargetTable())).append('\n');
        }

        yaml.append("pipeline:\n");
        yaml.append("  name: ").append(quote("cdc-pipeline-" + pipeline.getId() + "-" + pipeline.getName())).append('\n');
        yaml.append("  parallelism: ").append(parallelism).append('\n');
        return yaml.toString();
    }

    /**
     * 平台启动位点 → Flink scan.startup.mode 映射：
     * INITIAL → initial（全量快照+增量）/ LATEST_OFFSET → latest-offset / EARLIEST_OFFSET → earliest-offset。
     */
    private String toFlinkStartupMode(CdcPipeline pipeline) {
        return switch (pipeline.getStartupMode()) {
            case CdcPipeline.STARTUP_MODE_INITIAL -> "initial";
            case CdcPipeline.STARTUP_MODE_LATEST_OFFSET -> "latest-offset";
            case CdcPipeline.STARTUP_MODE_EARLIEST_OFFSET -> "earliest-offset";
            default -> throw new BusinessException(ErrorCode.CDC_PIPELINE_START_FAILED,
                    "非法的启动位点: " + pipeline.getStartupMode());
        };
    }

    /** YAML 字符串值统一单引号包裹（内容中的单引号按 YAML 规范双写转义） */
    private String quote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }
}
