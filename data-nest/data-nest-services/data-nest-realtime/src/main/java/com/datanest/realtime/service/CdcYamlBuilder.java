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
 * MySQL/PostgreSQL source → Iceberg sink（Hadoop Catalog + S3A 直写 MinIO）+ route 表级路由。
 * <p>
 * PG source 关键差异（Sprint 8 F2 PG 扩展，已对照 connector jar 确认）：
 * factory 标识 {@code postgres}；tables 格式 {@code db.schema.table}（本期仅 public schema）；
 * route 的 source-table 用 {@code schema.table}（table-id.include-database 默认 false，
 * TableId 为 (schema, table) 两段式）；slot.name 每管道唯一（datanest_cdc_&lt;pipelineId&gt;）；
 * scan.startup.mode 仅支持 initial/snapshot/latest-offset/committed-offset（无 earliest-offset）。
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
     * @param sourceType    源数据源类型（MYSQL / POSTGRESQL，见 {@link SourcePrecheckService}）
     * @param host          源库 host（容器内网地址）
     * @param port          源库端口
     * @param username      源库用户名
     * @param plainPassword 源库明文密码（已解密）
     */
    public String build(CdcPipeline pipeline, List<CdcPipelineTable> tables, String sourceType,
                        String host, Integer port, String username, String plainPassword) {
        boolean postgres = SourcePrecheckService.TYPE_POSTGRESQL.equalsIgnoreCase(sourceType);
        String startupMode = toFlinkStartupMode(pipeline, postgres);

        StringBuilder yaml = new StringBuilder();
        if (postgres) {
            appendPostgresSource(yaml, pipeline, tables, host, port, username, plainPassword, startupMode);
        } else {
            appendMysqlSource(yaml, pipeline, tables, host, port, username, plainPassword, startupMode);
        }

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
            // route 匹配源端 TableId：MySQL 为 (db, table)；PG 默认 table-id.include-database=false，
            // TableId 为 (schema, table)，故 source-table 用 public.<表名>
            String sourceTableId = postgres
                    ? SourcePrecheckService.PG_SCHEMA + "." + table.getSourceTable()
                    : pipeline.getSourceDatabase() + "." + table.getSourceTable();
            yaml.append("  - source-table: ").append(quote(sourceTableId)).append('\n');
            yaml.append("    sink-table: ").append(quote(pipeline.getTargetDatabase() + "." + table.getTargetTable())).append('\n');
        }

        yaml.append("pipeline:\n");
        yaml.append("  name: ").append(quote("cdc-pipeline-" + pipeline.getId() + "-" + pipeline.getName())).append('\n');
        yaml.append("  parallelism: ").append(parallelism).append('\n');
        return yaml.toString();
    }

    /** MySQL source 段（binlog server-id 区间 + jdbc 公钥检索透传） */
    private void appendMysqlSource(StringBuilder yaml, CdcPipeline pipeline, List<CdcPipelineTable> tables,
                                   String host, Integer port, String username, String plainPassword,
                                   String startupMode) {
        // server-id：同源并发管道靠管道 id 取模错开区间（每条管道占 100 个 id 区间）；
        // 区间起点 5400 + (id % 100) * 100，100 个区间内冲突概率可接受
        long serverIdBase = 5400 + (pipeline.getId() % 100) * 100L;
        String serverIdRange = serverIdBase + "-" + (serverIdBase + 99);

        String tableList = tables.stream()
                .map(t -> pipeline.getSourceDatabase() + "." + t.getSourceTable())
                .collect(Collectors.joining(","));

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
    }

    /**
     * PostgreSQL source 段（逻辑解码槽 + pgoutput）。
     * tables 格式 db.schema.table；slot.name 每管道唯一，避免同库多管道共槽互相推进 LSN。
     */
    private void appendPostgresSource(StringBuilder yaml, CdcPipeline pipeline, List<CdcPipelineTable> tables,
                                      String host, Integer port, String username, String plainPassword,
                                      String startupMode) {
        String tableList = tables.stream()
                .map(t -> pipeline.getSourceDatabase() + "." + SourcePrecheckService.PG_SCHEMA + "." + t.getSourceTable())
                .collect(Collectors.joining(","));

        yaml.append("source:\n");
        yaml.append("  type: postgres\n");
        yaml.append("  name: PostgreSQL Source\n");
        yaml.append("  hostname: ").append(quote(host)).append('\n');
        yaml.append("  port: ").append(port).append('\n');
        yaml.append("  username: ").append(quote(username)).append('\n');
        yaml.append("  password: ").append(quote(plainPassword)).append('\n');
        yaml.append("  tables: ").append(quote(tableList)).append('\n');
        // 复制槽名仅允许小写字母/数字/下划线；每管道唯一（同名槽并发占用会直接报错）
        yaml.append("  slot.name: ").append(quote("datanest_cdc_" + pipeline.getId())).append('\n');
        yaml.append("  decoding.plugin.name: pgoutput\n");
        // 仅无 savepoint 恢复时生效（有 savepoint 时 Flink 从 savepoint 状态续跑，忽略启动位点）
        yaml.append("  scan.startup.mode: ").append(startupMode).append('\n');
    }

    /**
     * 平台启动位点 → Flink scan.startup.mode 映射：
     * INITIAL → initial（全量快照+增量）/ LATEST_OFFSET → latest-offset / EARLIEST_OFFSET → earliest-offset。
     * PG connector 无 earliest-offset（仅 initial/snapshot/latest-offset/committed-offset），此处兜底拦截
     * （保存期 validateSaveRequest 已拦截，双保险）。
     */
    private String toFlinkStartupMode(CdcPipeline pipeline, boolean postgres) {
        return switch (pipeline.getStartupMode()) {
            case CdcPipeline.STARTUP_MODE_INITIAL -> "initial";
            case CdcPipeline.STARTUP_MODE_LATEST_OFFSET -> "latest-offset";
            case CdcPipeline.STARTUP_MODE_EARLIEST_OFFSET -> {
                if (postgres) {
                    throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                            "PostgreSQL 源不支持「从最早位点」启动（connector 仅支持 initial/latest-offset），"
                                    + "请改用「从最新位点」或「全量+增量」");
                }
                yield "earliest-offset";
            }
            default -> throw new BusinessException(ErrorCode.CDC_PIPELINE_START_FAILED,
                    "非法的启动位点: " + pipeline.getStartupMode());
        };
    }

    /** YAML 字符串值统一单引号包裹（内容中的单引号按 YAML 规范双写转义） */
    private String quote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }
}
