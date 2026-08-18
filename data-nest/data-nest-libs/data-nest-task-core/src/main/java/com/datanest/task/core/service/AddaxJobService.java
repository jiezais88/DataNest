package com.datanest.task.core.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.constant.SyncMode;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.common.util.JdbcUrlBuilder;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.FieldMappingItemDTO;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.task.core.dto.FieldMappingItem;
import com.datanest.task.core.dto.SourceTableDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 构建并执行 Addax 同步任务。
 * <p>
 * 目标端固定为 DataNest 内置 Doris，配置从 Nacos / Spring 环境读取。
 * <p>
 * 注意：Addax {@code ConfigParser.upgradeJobConfig()} 在解析 job.json 时，若 {@code job.content} 为数组，
 * 只会取第一个元素执行。因此多表同步必须按表拆分为独立的 Addax job 顺序执行，而不是在一个 job 文件里放多个 content。
 */
@Service
public class AddaxJobService {

    private static final Logger logger = LoggerFactory.getLogger(AddaxJobService.class);
    private static final int DEFAULT_CHANNEL = 3;
    private static final int DEFAULT_TIMEOUT_SECONDS = 1800;
    /** 手动停止轮询间隔：worker 阻塞在 readLine()，靠 watcher 轮询 engineering 发现 TERMINATED 后杀进程 */
    private static final long STOP_WATCH_INTERVAL_MS = 2000L;

    @Value("${datanest.addax.home:/opt/addax}")
    private String addaxHome;

    @Value("${datanest.engineering.addax.job-path:/opt/addax/job}")
    private String jobPath;

    @Value("${datanest.engineering.addax.log-path:/opt/addax/log}")
    private String logPath;

    @Value("${datanest.addax.writer.fe-load-url:http://localhost:8030}")
    private String dorisFeLoadUrl;

    @Value("${datanest.addax.writer.be-load-url:http://localhost:8040}")
    private String dorisBeLoadUrl;

    @Value("${datanest.addax.writer.username:root}")
    private String dorisUsername;

    @Value("${datanest.addax.writer.password:}")
    private String dorisPassword;

    @Value("${datanest.addax.writer.load-props.format:json}")
    private String loadPropsFormat;

    @Value("${datanest.addax.writer.load-props.strip-outer-array:true}")
    private String loadPropsStripOuterArray;

    @Value("${datanest.addax.writer.line-delimiter:\n}")
    private String lineDelimiter;

    @Value("${datanest.doris.fe-host:localhost}")
    private String dorisFeHost;

    @Value("${datanest.doris.fe-query-port:9030}")
    private int dorisFeQueryPort;

    @Value("${datanest.doris.user:root}")
    private String dorisQueryUser;

    @Value("${datanest.doris.password:}")
    private String dorisQueryPassword;

    private final EngineeringSyncJobApi syncJobApi;
    private final EngineeringDatasourceApi datasourceApi;
    private final AddaxLogParser addaxLogParser;
    private final ObjectMapper objectMapper;
    private final EncryptionConfig encryptionConfig;
    private final IncrementalFieldTypeResolver incrementalFieldTypeResolver;

    public AddaxJobService(EngineeringSyncJobApi syncJobApi, EngineeringDatasourceApi datasourceApi,
                           AddaxLogParser addaxLogParser, ObjectMapper objectMapper,
                           EncryptionConfig encryptionConfig,
                           IncrementalFieldTypeResolver incrementalFieldTypeResolver) {
        this.syncJobApi = syncJobApi;
        this.datasourceApi = datasourceApi;
        this.addaxLogParser = addaxLogParser;
        this.objectMapper = objectMapper;
        this.encryptionConfig = encryptionConfig;
        this.incrementalFieldTypeResolver = incrementalFieldTypeResolver;
    }

    /**
     * 执行指定同步任务的 Addax 作业。
     * <p>
     * Addax 本身不支持一个 job.json 里配置多个 content，因此多表场景下按源表逐张生成独立 job 文件并顺序执行，
     * 最后聚合读取/写入行数、错误行数与日志。
     *
     * @param syncJobId 同步任务 ID
     * @param historyId 执行历史 ID，用于手动停止轮询；为 null 时不启动 watcher
     * @return 执行结果，包含聚合后的日志行列表
     */
    public AddaxExecutionResult execute(Long syncJobId, Long historyId) {
        // 执行开始处 fail-fast：任务/数据源读不到直接抛错，不跑"无登记执行"
        SyncJobInfo job = getJobOrThrow(syncJobId);
        DataSourceInfo source = getDatasourceOrThrow(job.getSourceDatasourceId());

        List<String> sourceTables = job.getSourceTables();
        if (sourceTables == null || sourceTables.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步任务源表为空: syncJobId=" + syncJobId);
        }

        Map<String, SourceTableDetail> detailMap = parseSourceTablesDetail(job);

        long totalReadRows = 0L;
        long totalWriteRows = 0L;
        long totalErrorRows = 0L;
        List<String> aggregatedLogLines = new ArrayList<>();
        List<String> failedTableMessages = new ArrayList<>();
        List<TableResult> tableResults = new ArrayList<>();
        String lastLogPath = null;

        logger.info("开始逐表执行 Addax 同步: syncJobId={}, sourceTables={}, historyId={}",
                syncJobId, sourceTables, historyId);

        for (int i = 0; i < sourceTables.size(); i++) {
            String sourceTable = sourceTables.get(i);
            SourceTableDetail detail = detailMap.get(sourceTable);
            String tableLabel = sourceTable + "(" + (i + 1) + "/" + sourceTables.size() + ")";
            String targetTableName = resolveTargetTableName(job, sourceTable, detail);
            String safeTableName = safeFileName(sourceTable);
            Path jobFilePath = Paths.get(jobPath, "job_sync_" + syncJobId + "_" + safeTableName + ".json");
            Path logFilePath = Paths.get(logPath, "sync_" + syncJobId + "_" + safeTableName + ".log");
            lastLogPath = logFilePath.toString();

            String jobJson = generateJobJson(job, source, sourceTable, detail);
            try {
                Files.createDirectories(jobFilePath.getParent());
                Files.createDirectories(logFilePath.getParent());
                Files.writeString(jobFilePath, jobJson, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                logger.info("Addax job config written: syncJobId={}, table={}, path={}", syncJobId, sourceTable, jobFilePath);
            } catch (IOException e) {
                logger.error("写入 Addax 任务文件失败: syncJobId={}, table={}, path={}", syncJobId, sourceTable, jobFilePath, e);
                throw new BusinessException(ErrorCode.ADDAX_EXECUTION_FAILED,
                        "写入 Addax 任务文件失败 [" + sourceTable + "]: " + e.getMessage());
            }

            long tableStartMs = System.currentTimeMillis();
            AddaxExecutionResult tableResult = runAddax(syncJobId, historyId, jobFilePath, logFilePath, job, source, tableLabel);
            long tableDurationMs = System.currentTimeMillis() - tableStartMs;

            aggregatedLogLines.add("===== Addax 执行: " + sourceTable + " =====");
            aggregatedLogLines.addAll(tableResult.logLines());

            totalReadRows += Math.max(0, tableResult.readRows());
            totalWriteRows += Math.max(0, tableResult.writeRows());
            totalErrorRows += Math.max(0, tableResult.errorRows());

            tableResults.add(new TableResult(sourceTable, targetTableName,
                    tableResult.success() ? ExecutionStatus.SUCCESS.getCode() : ExecutionStatus.FAILED.getCode(),
                    tableResult.readRows(), tableResult.writeRows(), tableDurationMs,
                    tableResult.success() ? null : tableResult.errorMessage(),
                    tableResult.logLines()));

            // 单表失败记录错误信息但继续执行后续表，保证多表同步不因一张表失败而中断其余表
            if (!tableResult.success()) {
                failedTableMessages.add("[" + sourceTable + "] "
                        + (StringUtils.hasText(tableResult.errorMessage()) ? tableResult.errorMessage() : "Addax 执行失败"));
                logger.error("单表同步失败，继续执行后续表: syncJobId={}, failedTable={}, message={}",
                        syncJobId, sourceTable, tableResult.errorMessage());
            }
        }

        boolean overallSuccess = failedTableMessages.isEmpty();
        String errorMessage = overallSuccess ? null
                : failedTableMessages.size() + " 张表同步失败: " + String.join("；", failedTableMessages);
        // 将聚合日志写入最后一个 log 文件（或单独写一个聚合日志），便于历史日志查看
        if (lastLogPath != null) {
            Path aggregatedLogPath = Paths.get(lastLogPath).getParent().resolve("sync_" + syncJobId + "_aggregated.log");
            try {
                Files.writeString(aggregatedLogPath, String.join("\n", aggregatedLogLines), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                logger.warn("写入聚合日志文件失败: syncJobId={}, path={}", syncJobId, aggregatedLogPath, e);
            }
        }

        return new AddaxExecutionResult(overallSuccess, totalReadRows, totalWriteRows, totalErrorRows,
                errorMessage, lastLogPath, aggregatedLogLines, tableResults);
    }

    private String generateJobJson(SyncJobInfo job, DataSourceInfo source,
                                   String sourceTable, SourceTableDetail detail) {
        String sourceDb = StringUtils.hasText(job.getSourceDatabase()) ? job.getSourceDatabase()
                : (StringUtils.hasText(job.getSourceSchema()) ? job.getSourceSchema() : "default");
        String targetTableName = resolveTargetTableName(job, sourceTable, detail);
        Map<String, Object> reader = buildReader(job, source, sourceDb, sourceTable, detail);
        Map<String, Object> writer = buildWriter(job, targetTableName, detail);

        Map<String, Object> content = new HashMap<>();
        content.put("reader", reader);
        content.put("writer", writer);

        Map<String, Object> speed = new HashMap<>();
        speed.put("channel", DEFAULT_CHANNEL);
        // Sprint 3 AC-13：速率限流（全局限速放在 job.setting.speed 下）
        Long bytePerChannel = null;
        Long recordPerChannel = null;
        if (job.getRateLimitEnabled() != null && job.getRateLimitEnabled() == 1) {
            if (job.getReadRateLimitMbps() != null && job.getReadRateLimitMbps() > 0) {
                // Addax speed.byte 单位 Byte/s；Mbps -> Byte/s：* 1024 * 1024 / 8
                long bytePerSecond = job.getReadRateLimitMbps() * 1024L * 1024L / 8L;
                bytePerChannel = Math.max(1L, bytePerSecond / DEFAULT_CHANNEL);
                speed.put("byte", bytePerSecond);
            }
            if (job.getWriteRateLimitRowsPerSecond() != null && job.getWriteRateLimitRowsPerSecond() > 0) {
                long recordPerSecond = job.getWriteRateLimitRowsPerSecond();
                recordPerChannel = Math.max(1L, recordPerSecond / DEFAULT_CHANNEL);
                speed.put("record", recordPerSecond);
            }
        }

        Map<String, Object> setting = new HashMap<>();
        setting.put("speed", speed);

        Map<String, Object> jobMap = new HashMap<>();
        jobMap.put("setting", setting);
        jobMap.put("content", content);

        Map<String, Object> root = new HashMap<>();
        root.put("job", jobMap);

        // Addax 单 channel 限速读取 core.transport.channel.speed.byte/record，需放在根节点
        if (bytePerChannel != null || recordPerChannel != null) {
            Map<String, Object> channelSpeed = new HashMap<>();
            if (bytePerChannel != null) {
                channelSpeed.put("byte", bytePerChannel);
            }
            if (recordPerChannel != null) {
                channelSpeed.put("record", recordPerChannel);
            }
            Map<String, Object> channel = new HashMap<>();
            channel.put("speed", channelSpeed);
            Map<String, Object> transport = new HashMap<>();
            transport.put("channel", channel);
            Map<String, Object> core = new HashMap<>();
            core.put("transport", transport);
            root.put("core", core);
        }

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            logger.error("序列化 Addax 任务配置失败: syncJobId={}, table={}", job.getId(), sourceTable, e);
            throw new BusinessException(ErrorCode.ADDAX_EXECUTION_FAILED, "生成 Addax 配置失败: " + e.getMessage());
        }
    }

    private Map<String, SourceTableDetail> parseSourceTablesDetail(SyncJobInfo job) {
        if (!StringUtils.hasText(job.getSourceTablesDetail())) {
            return Map.of();
        }
        try {
            List<SourceTableDetail> details = com.datanest.common.json.JsonUtils.parseArray(job.getSourceTablesDetail(), SourceTableDetail.class);
            Map<String, SourceTableDetail> map = new HashMap<>();
            for (SourceTableDetail d : details) {
                if (StringUtils.hasText(d.getSourceTable())) {
                    map.put(d.getSourceTable(), d);
                }
            }
            return map;
        } catch (Exception e) {
            logger.warn("解析 sourceTablesDetail 失败，将使用默认目标表映射: syncJobId={}", job.getId(), e);
            return Map.of();
        }
    }

    private String resolveTargetTableName(SyncJobInfo job, String sourceTable, SourceTableDetail detail) {
        if (detail != null && StringUtils.hasText(detail.getTargetTable())) {
            return detail.getTargetTable();
        }
        if (StringUtils.hasText(job.getTargetTable())) {
            return job.getTargetTable();
        }
        String sourceDb = StringUtils.hasText(job.getSourceDatabase()) ? job.getSourceDatabase()
                : (StringUtils.hasText(job.getSourceSchema()) ? job.getSourceSchema() : "default");
        String db = sourceDb.replaceAll("[^a-zA-Z0-9_]", "_");
        String table = sourceTable.replaceAll("[^a-zA-Z0-9_]", "_");
        return "sync_" + db + "_" + table;
    }

    private Map<String, Object> buildReader(SyncJobInfo job, DataSourceInfo source,
                                            String sourceDb, String sourceTable,
                                            SourceTableDetail detail) {
        String jdbcUrl = JdbcUrlBuilder.buildJdbcUrl(source.getType(), source.getHost(), source.getPort(),
                source.getDatabaseName(), source.getSchemaName());
        List<String> columns = buildReaderColumns(job, detail);
        boolean incremental = SyncMode.INCREMENTAL.getCode().equalsIgnoreCase(job.getSyncMode());

        Map<String, Object> connectionItem = new HashMap<>();
        connectionItem.put("jdbcUrl", List.of(jdbcUrl));
        connectionItem.put("table", List.of(sourceTable));

        Map<String, Object> parameter = new HashMap<>();
        parameter.put("username", source.getUsername());
        parameter.put("password", encryptionConfig.decrypt(source.getEncryptedPassword()));
        parameter.put("connection", List.of(connectionItem));
        parameter.put("column", columns);

        if (incremental && StringUtils.hasText(job.getIncrementalField())) {
            String maxValue = queryIncrementalMaxValue(job, source, sourceTable, detail);
            String columnList = columns.isEmpty() || columns.contains("*") ? "*" : String.join(", ", columns);
            String querySql;
            if (maxValue != null) {
                querySql = "SELECT " + columnList + " FROM " + quoteIdentifier(source.getType(), sourceTable)
                        + " WHERE " + quoteIdentifier(source.getType(), job.getIncrementalField()) + " > " + maxValue;
            } else {
                querySql = "SELECT " + columnList + " FROM " + quoteIdentifier(source.getType(), sourceTable);
            }
            parameter.put("querySql", List.of(querySql));
        }

        Map<String, Object> reader = new HashMap<>();
        reader.put("name", resolveReaderName(source.getType()));
        reader.put("parameter", parameter);
        return reader;
    }

    private String quoteIdentifier(String dbType, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        DataSourceType type = DataSourceType.fromCode(dbType);
        if (type == null) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return switch (type) {
            case POSTGRESQL, ORACLE -> "\"" + identifier.replace("\"", "\"\"") + "\"";
            case MYSQL, DORIS -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
        };
    }

    private String queryIncrementalMaxValue(SyncJobInfo job, DataSourceInfo source,
                                            String sourceTable, SourceTableDetail detail) {
        // 首次成功同步之前，直接全量拉取；远程失败降级按 0（全量）处理
        Long successCount = RemoteCalls.execute("engineering.sync-history.success-count", () -> {
            Result<Long> result = syncJobApi.successCount(job.getId());
            return result == null || result.data() == null ? 0L : result.data();
        }, 0L);
        if (successCount <= 0) {
            return null;
        }

        IncrementalFieldTypeResolver.TypeCategory sourceCategory = incrementalFieldTypeResolver
                .resolveSourceFieldType(source, job, sourceTable, job.getIncrementalField());
        String targetTableName = resolveTargetTableName(job, sourceTable, detail);
        String targetDb = resolveTargetDatabase(job);
        IncrementalFieldTypeResolver.TypeCategory targetCategory = incrementalFieldTypeResolver
                .resolveTargetFieldType(targetDb, targetTableName, job.getIncrementalField());

        if (!incrementalFieldTypeResolver.isComparable(sourceCategory, targetCategory)) {
            logger.warn("增量字段源端与目标端类型不一致或不是可比较类型，将按全量同步处理: syncJobId={}, table={}, field={}, sourceCategory={}, targetCategory={}",
                    job.getId(), sourceTable, job.getIncrementalField(), sourceCategory, targetCategory);
            return null;
        }

        String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000",
                dorisFeHost, dorisFeQueryPort, targetDb);
        String sql = "SELECT MAX(" + quoteIdentifier(DataSourceType.DORIS.getCode(), job.getIncrementalField()) + ") AS max_value FROM "
                + quoteIdentifier(DataSourceType.DORIS.getCode(), targetTableName);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dorisQueryUser, dorisQueryPassword);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                Object value = rs.getObject("max_value");
                if (value != null) {
                    return incrementalFieldTypeResolver.formatMaxValue(value, targetCategory);
                }
            }
        } catch (Exception e) {
            logger.warn("查询目标表增量最大值失败，将按全量同步处理: syncJobId={}, table={}, targetTable={}, field={}",
                    job.getId(), sourceTable, targetTableName, job.getIncrementalField(), e);
        }
        return null;
    }

    private Map<String, Object> buildWriter(SyncJobInfo job, String targetTableName, SourceTableDetail detail) {
        List<String> columns = buildWriterColumns(job, detail);
        String targetDb = resolveTargetDatabase(job);

        Map<String, Object> loadProps = new HashMap<>();
        loadProps.put("format", loadPropsFormat);
        loadProps.put("strip_outer_array", String.valueOf(Boolean.parseBoolean(loadPropsStripOuterArray)));
        loadProps.put("line_delimiter", lineDelimiter);

        Map<String, Object> connectionItem = new HashMap<>();
        connectionItem.put("jdbcUrl", String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC",
                dorisFeHost, dorisFeQueryPort, targetDb));
        connectionItem.put("database", targetDb);
        connectionItem.put("table", List.of(targetTableName));

        Map<String, Object> parameter = new HashMap<>();
        parameter.put("loadUrl", List.of(stripProtocol(dorisFeLoadUrl)));
        parameter.put("username", dorisUsername);
        parameter.put("password", dorisPassword);
        parameter.put("column", columns);
        parameter.put("connection", List.of(connectionItem));
        parameter.put("loadProps", loadProps);

        Map<String, Object> writer = new HashMap<>();
        writer.put("name", "doriswriter");
        writer.put("parameter", parameter);
        return writer;
    }

    private String stripProtocol(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceFirst("^https?://", "");
    }

    private String resolveTargetDatabase(SyncJobInfo job) {
        if (!StringUtils.hasText(job.getTargetDatabase())) {
            throw new IllegalArgumentException("目标库名不能为空: syncJobId=" + job.getId());
        }
        return job.getTargetDatabase();
    }

    private List<String> buildReaderColumns(SyncJobInfo job, SourceTableDetail detail) {
        if (detail != null && detail.getFieldMapping() != null) {
            List<FieldMappingItem> mapping = detail.getFieldMapping();
            return mapping.isEmpty() ? List.of("*") : mapping.stream()
                    .map(FieldMappingItem::getSourceColumn)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
        List<FieldMappingItemDTO> mapping = job.getFieldMapping();
        if (mapping != null && !mapping.isEmpty()) {
            return mapping.stream()
                    .map(FieldMappingItemDTO::getSourceColumn)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
        return List.of("*");
    }

    private List<String> buildWriterColumns(SyncJobInfo job, SourceTableDetail detail) {
        if (detail != null && detail.getFieldMapping() != null) {
            List<FieldMappingItem> mapping = detail.getFieldMapping();
            return mapping.isEmpty() ? List.of("*") : mapping.stream()
                    .map(FieldMappingItem::getTargetColumn)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
        List<FieldMappingItemDTO> mapping = job.getFieldMapping();
        if (mapping != null && !mapping.isEmpty()) {
            return mapping.stream()
                    .map(FieldMappingItemDTO::getTargetColumn)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
        return List.of("*");
    }

    private String resolveReaderName(String type) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported reader type: " + type);
        }
        return switch (dataSourceType) {
            case POSTGRESQL -> "postgresqlreader";
            case MYSQL, DORIS -> "mysqlreader";
            case ORACLE -> "oraclereader";
            case SQLSERVER -> "sqlserverreader";
        };
    }

    private AddaxExecutionResult runAddax(Long syncJobId, Long historyId, Path jobFilePath, Path logFilePath,
                                          SyncJobInfo job, DataSourceInfo source, String tableLabel) {
        String addaxSh = Paths.get(addaxHome, "bin", "addax.sh").toString();
        List<String> command = List.of(addaxSh, jobFilePath.toString());
        logger.info("启动 Addax: syncJobId={}, table={}, command={}, workDir={}", syncJobId, tableLabel, command, addaxHome);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(addaxHome));
        processBuilder.redirectErrorStream(true);

        List<String> logLines = new ArrayList<>();
        Process process = null;
        try {
            process = processBuilder.start();
            // 手动停止协作点：本线程阻塞在 readLine()，由 watcher 轮询 DB 发现 TERMINATED 后强杀子进程
            if (historyId != null) {
                startStopWatcher(process, historyId, syncJobId);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logLines.add(line);
                }
            }

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int exitCode;
            if (!finished) {
                process.destroyForcibly();
                logLines.add("Addax 执行超时");
                logger.error("Addax 执行超时: syncJobId={}, table={}", syncJobId, tableLabel);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }
            String logContent = String.join("\n", logLines);
            Files.writeString(logFilePath, logContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Addax 日志已写入: syncJobId={}, table={}, path={}, exitCode={}",
                    syncJobId, tableLabel, logFilePath, exitCode);

            AddaxLogParser.AddaxParseResult parseResult = addaxLogParser.parse(logLines);
            boolean success = exitCode == 0 && parseResult.errorRows() == 0 && parseResult.errorLines().isEmpty();
            return new AddaxExecutionResult(success,
                    parseResult.readRows(), parseResult.writeRows(), parseResult.errorRows(),
                    success ? null : buildErrorMessage(parseResult, exitCode),
                    logFilePath.toString(), logLines, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断兜底：线程被打断时子进程仍在跑，必须强杀避免 Addax 孤儿进程
            if (process != null) {
                process.destroyForcibly();
            }
            logger.error("Addax 执行被中断: syncJobId={}, table={}", syncJobId, tableLabel, e);
            return new AddaxExecutionResult(false, 0, 0, 0,
                    "Addax 执行被中断: " + e.getMessage(), logFilePath.toString(), logLines, List.of());
        } catch (Exception e) {
            logger.error("Addax 执行异常: syncJobId={}, table={}", syncJobId, tableLabel, e);
            return new AddaxExecutionResult(false, 0, 0, 0,
                    "Addax 执行异常: " + e.getMessage(), logFilePath.toString(), logLines, List.of());
        }
    }

    /**
     * 手动停止 watcher：每 2 秒经 getHistory 轻量端点重查 history 状态，发现 TERMINATED 则强杀 Addax 子进程。
     * daemon 线程 + 进程存活判断保证进程结束后 watcher 随之退出。
     * 远程失败本轮按「未停止」处理继续跑（连续失败记 warn）。
     */
    private void startStopWatcher(Process process, Long historyId, Long syncJobId) {
        Thread watcher = new Thread(() -> {
            while (process.isAlive()) {
                try {
                    Thread.sleep(STOP_WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    Result<SyncHistoryInfo> result = syncJobApi.getHistory(historyId);
                    SyncHistoryInfo history = result == null ? null : result.data();
                    if (history != null && ExecutionStatus.TERMINATED.getCode().equalsIgnoreCase(history.getStatus())) {
                        logger.info("检测到手动停止，强杀 Addax 子进程: syncJobId={}, historyId={}", syncJobId, historyId);
                        process.destroyForcibly();
                        return;
                    }
                } catch (Exception e) {
                    // 轮询失败（如 engineering 瞬断）不致命，按未停止处理，下一周期重试
                    logger.warn("轮询手动停止状态失败，继续等待: syncJobId={}, historyId={}", syncJobId, historyId, e);
                }
            }
        }, "addax-stop-watcher-" + historyId);
        watcher.setDaemon(true);
        watcher.start();
    }

    // ==================== engineering 远程读取（fail-fast，DTO 直接作为内存模型） ====================

    private SyncJobInfo getJobOrThrow(Long syncJobId) {
        Result<SyncJobInfo> result = syncJobApi.getById(syncJobId);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        return result.data();
    }

    private DataSourceInfo getDatasourceOrThrow(Long datasourceId) {
        Result<DataSourceInfo> result = datasourceApi.getById(datasourceId);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "源数据源不存在: " + datasourceId);
        }
        return result.data();
    }

    private String buildErrorMessage(AddaxLogParser.AddaxParseResult parseResult, int exitCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Addax 执行失败，exitCode=").append(exitCode);
        if (parseResult.errorRows() > 0) {
            sb.append("，失败行数=").append(parseResult.errorRows());
        }
        if (!parseResult.errorLines().isEmpty()) {
            sb.append("；").append(String.join("；", parseResult.errorLines()));
        }
        return sb.toString();
    }

    private String safeFileName(String sourceTable) {
        if (!StringUtils.hasText(sourceTable)) {
            return "unknown";
        }
        return sourceTable.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public record AddaxExecutionResult(boolean success, long readRows, long writeRows, long errorRows,
                                       String errorMessage, String logPath, List<String> logLines,
                                       List<TableResult> tableResults) {
    }

    /**
     * 单表同步结果明细（多表同步按表记录，供历史详情按表展示）。
     */
    public record TableResult(String sourceTable, String targetTable, String status,
                              long readRows, long writeRows, long durationMs, String errorMessage,
                              List<String> logLines) {
    }
}
