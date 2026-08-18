package com.datanest.realtime.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.realtime.entity.CdcPipeline;
import com.datanest.realtime.entity.CdcPipelineTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
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

    /** configJson 高级配置约定键：作业并行度（1~8） */
    public static final String CONFIG_KEY_PARALLELISM = "parallelism";
    /** configJson 高级配置约定键：checkpoint 间隔秒（≥3，下发时 ×1000 转毫秒） */
    public static final String CONFIG_KEY_CHECKPOINT_INTERVAL_SECONDS = "checkpointIntervalSeconds";
    /** configJson 高级配置约定键：表结构变更策略（EVOLVE/LENIENT/EXCEPTION，缺省走 connector 默认 EVOLVE） */
    public static final String CONFIG_KEY_SCHEMA_CHANGE_BEHAVIOR = "schemaChangeBehavior";
    /** configJson 高级配置约定键：快照分块大小（scan.incremental.snapshot.chunk.size，大表快照调优） */
    public static final String CONFIG_KEY_SCAN_CHUNK_SIZE = "scanChunkSize";

    /** 表结构变更策略合法值（CDC YAML pipeline 段 schema.change.behavior） */
    public static final Set<String> SCHEMA_CHANGE_BEHAVIORS = Set.of("EVOLVE", "LENIENT", "EXCEPTION");

    /** 高级配置解析结果（configJson 约定键；null = 缺键走默认） */
    public record AdvancedConfig(Integer parallelism, Integer checkpointIntervalSeconds,
                                 String schemaChangeBehavior, Integer scanChunkSize) {
    }

    /**
     * 解析 configJson 高级配置键（fastjson2，项目统一 JSON 库）。
     * 空/缺键返回对应字段 null；非法 JSON、整数键值非整数时抛 8000 参数错误（不向上抛 500）。
     */
    public static AdvancedConfig parseAdvancedConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new AdvancedConfig(null, null, null, null);
        }
        tools.jackson.databind.node.ObjectNode json;
        try {
            json = com.datanest.common.json.JsonUtils.parseObject(configJson);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "configJson 不是合法 JSON: " + e.getMessage());
        }
        if (json == null) {
            return new AdvancedConfig(null, null, null, null);
        }
        try {
            String behavior = com.datanest.common.json.JsonUtils.getString(json, CONFIG_KEY_SCHEMA_CHANGE_BEHAVIOR);
            return new AdvancedConfig(
                    com.datanest.common.json.JsonUtils.getInteger(json, CONFIG_KEY_PARALLELISM),
                    com.datanest.common.json.JsonUtils.getInteger(json, CONFIG_KEY_CHECKPOINT_INTERVAL_SECONDS),
                    behavior == null || behavior.isBlank() ? null : behavior.trim().toUpperCase(),
                    com.datanest.common.json.JsonUtils.getInteger(json, CONFIG_KEY_SCAN_CHUNK_SIZE));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CDC_PIPELINE_CONFIG_INVALID,
                    "configJson 高级配置整数键必须为整数（parallelism / checkpointIntervalSeconds / scanChunkSize）: "
                            + e.getMessage());
        }
    }

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

    /** Kafka 事件总线地址（F4 事件作业 sink；data-service 消费侧同键） */
    @Value("${datanest.kafka.bootstrap-servers:middleware-kafka:9092}")
    private String kafkaBootstrapServers;

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
        // 高级配置（configJson）解析；保存期 validateSaveRequest 已校验范围/枚举
        AdvancedConfig advanced = parseAdvancedConfig(pipeline.getConfigJson());

        StringBuilder yaml = new StringBuilder();
        if (postgres) {
            appendPostgresSource(yaml, pipeline, tables, host, port, username, plainPassword, startupMode,
                    advanced.scanChunkSize(), "datanest_cdc_" + pipeline.getId());
        } else {
            appendMysqlSource(yaml, pipeline, tables, host, port, username, plainPassword, startupMode,
                    advanced.scanChunkSize(), 5400 + (pipeline.getId() % 100) * 100L);
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
        // 高级配置（configJson）覆盖 Nacos 默认值；保存期 validateSaveRequest 已校验范围。
        // 注意：checkpointIntervalSeconds 不在此下发——FlinkPipelineComposer.compose 只消费 pipeline 段的
        // name/parallelism/schema.change.behavior 等固定键，任意键（如 execution.checkpointing.interval）
        // 不会并入作业配置（3.6.0-2.2 源码确认 + 实测 interval 不生效），checkpoint 间隔改由
        // FlinkJobService 提交侧 flinkConfig 覆盖。
        yaml.append("  parallelism: ")
                .append(advanced.parallelism() != null ? advanced.parallelism() : parallelism).append('\n');
        if (advanced.schemaChangeBehavior() != null) {
            yaml.append("  schema.change.behavior: ").append(advanced.schemaChangeBehavior()).append('\n');
        }
        return yaml.toString();
    }

    /**
     * 组装 F4 事件作业 CDC YAML（Kafka 单 sink，仅增量 latest-offset）。
     * <p>
     * source 段复用主管道（MySQL server-id 6400 区间 / PG 复制槽 {@code datanest_cdc_ev_} 前缀，错开主管道），
     * sink 段单 Kafka（debezium-json），topic 每管道专属 {@code cdc-events-{pipelineId}}。
     */
    public String buildEvent(CdcPipeline pipeline, List<CdcPipelineTable> tables, String sourceType,
                             String host, Integer port, String username, String plainPassword) {
        boolean postgres = SourcePrecheckService.TYPE_POSTGRESQL.equalsIgnoreCase(sourceType);
        StringBuilder yaml = new StringBuilder();
        // 事件作业固定 latest-offset（仅增量推送，q-3）；无快照，scanChunkSize 传 null
        if (postgres) {
            appendPostgresSource(yaml, pipeline, tables, host, port, username, plainPassword,
                    "latest-offset", null, "datanest_cdc_ev_" + pipeline.getId());
        } else {
            appendMysqlSource(yaml, pipeline, tables, host, port, username, plainPassword,
                    "latest-offset", null, 6400 + (pipeline.getId() % 100) * 100L);
        }

        yaml.append("sink:\n");
        yaml.append("  type: kafka\n");
        yaml.append("  name: Kafka Event Sink\n");
        yaml.append("  properties.bootstrap.servers: ").append(quote(kafkaBootstrapServers)).append('\n');
        yaml.append("  topic: ").append(quote("cdc-events-" + pipeline.getId())).append('\n');
        yaml.append("  value.format: debezium-json\n");

        yaml.append("pipeline:\n");
        yaml.append("  name: ")
                .append(quote("cdc-pipeline-events-" + pipeline.getId() + "-" + pipeline.getName())).append('\n');
        // 事件作业固定并行度 1（仅透传变更，无需高吞吐，省 TaskManager slot）
        yaml.append("  parallelism: 1\n");
        return yaml.toString();
    }

    /** MySQL source 段（binlog server-id 区间 + jdbc 公钥检索透传） */
    private void appendMysqlSource(StringBuilder yaml, CdcPipeline pipeline, List<CdcPipelineTable> tables,
                                   String host, Integer port, String username, String plainPassword,
                                   String startupMode, Integer scanChunkSize, long serverIdBase) {
        // server-id：同源并发管道靠管道 id 取模错开区间（每条管道占 100 个 id 区间）；
        // 主管道区间起点 5400，事件作业 6400（错开避免并发 binlog 干扰）
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
        // 高级配置：快照分块大小（大表快照吞吐调优；缺省走 connector 默认 8096）
        if (scanChunkSize != null) {
            yaml.append("  scan.incremental.snapshot.chunk.size: ").append(scanChunkSize).append('\n');
        }
    }

    /**
     * PostgreSQL source 段（逻辑解码槽 + pgoutput）。
     * tables 格式 db.schema.table；slot.name 每管道唯一，避免同库多管道共槽互相推进 LSN。
     */
    private void appendPostgresSource(StringBuilder yaml, CdcPipeline pipeline, List<CdcPipelineTable> tables,
                                      String host, Integer port, String username, String plainPassword,
                                      String startupMode, Integer scanChunkSize, String slotName) {
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
        yaml.append("  slot.name: ").append(quote(slotName)).append('\n');
        yaml.append("  decoding.plugin.name: pgoutput\n");
        // 仅无 savepoint 恢复时生效（有 savepoint 时 Flink 从 savepoint 状态续跑，忽略启动位点）
        yaml.append("  scan.startup.mode: ").append(startupMode).append('\n');
        // 高级配置：快照分块大小（大表快照吞吐调优；缺省走 connector 默认 8096）
        if (scanChunkSize != null) {
            yaml.append("  scan.incremental.snapshot.chunk.size: ").append(scanChunkSize).append('\n');
        }
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
