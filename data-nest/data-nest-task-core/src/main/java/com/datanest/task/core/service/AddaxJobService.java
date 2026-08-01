package com.datanest.task.core.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.constant.SyncMode;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.dto.FieldMappingItem;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import com.datanest.task.core.mapper.SyncJobMapper;
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
 */
@Service
public class AddaxJobService {

    private static final Logger logger = LoggerFactory.getLogger(AddaxJobService.class);
    private static final int DEFAULT_CHANNEL = 3;
    private static final int DEFAULT_TIMEOUT_SECONDS = 1800;

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

    @Value("${datanest.addax.writer.line-delimiter:\\n}")
    private String lineDelimiter;

    @Value("${datanest.doris.fe-host:localhost}")
    private String dorisFeHost;

    @Value("${datanest.doris.fe-query-port:9030}")
    private int dorisFeQueryPort;

    @Value("${datanest.doris.user:root}")
    private String dorisQueryUser;

    @Value("${datanest.doris.password:}")
    private String dorisQueryPassword;

    private final SyncJobMapper syncJobMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final ConnectionTester connectionTester;
    private final AddaxLogParser addaxLogParser;
    private final ObjectMapper objectMapper;
    private final EncryptionConfig encryptionConfig;
    private final IncrementalFieldTypeResolver incrementalFieldTypeResolver;

    public AddaxJobService(SyncJobMapper syncJobMapper, DataSourceConnectionMapper dataSourceConnectionMapper,
                           SyncJobHistoryMapper syncJobHistoryMapper, ConnectionTester connectionTester,
                           AddaxLogParser addaxLogParser, ObjectMapper objectMapper,
                           EncryptionConfig encryptionConfig,
                           IncrementalFieldTypeResolver incrementalFieldTypeResolver) {
        this.syncJobMapper = syncJobMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.connectionTester = connectionTester;
        this.addaxLogParser = addaxLogParser;
        this.objectMapper = objectMapper;
        this.encryptionConfig = encryptionConfig;
        this.incrementalFieldTypeResolver = incrementalFieldTypeResolver;
    }

    /**
     * 执行指定同步任务的 Addax 作业。
     *
     * @param syncJobId 同步任务 ID
     * @return 执行结果，包含原始日志行列表
     */
    public AddaxExecutionResult execute(Long syncJobId) {
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }

        DataSourceConnection source = dataSourceConnectionMapper.selectById(job.getSourceDatasourceId());
        if (source == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "源数据源不存在: " + job.getSourceDatasourceId());
        }

        String jobJson = generateJobJson(job, source);
        Path jobFilePath = Paths.get(jobPath, "job_sync_" + syncJobId + ".json");
        Path logFilePath = Paths.get(logPath, "sync_" + syncJobId + ".log");

        try {
            Files.createDirectories(jobFilePath.getParent());
            Files.createDirectories(logFilePath.getParent());
            Files.writeString(jobFilePath, jobJson, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Addax job config written: syncJobId={}, path={}", syncJobId, jobFilePath);
        } catch (IOException e) {
            logger.error("写入 Addax 任务文件失败: syncJobId={}, path={}", syncJobId, jobFilePath, e);
            throw new BusinessException(ErrorCode.ADDAX_EXECUTION_FAILED, "写入 Addax 任务文件失败: " + e.getMessage());
        }

        return runAddax(syncJobId, jobFilePath, logFilePath, job, source);
    }

    private String generateJobJson(SyncJob job, DataSourceConnection source) {
        String sourceDb = StringUtils.hasText(job.getSourceDatabase()) ? job.getSourceDatabase()
                : (StringUtils.hasText(job.getSourceSchema()) ? job.getSourceSchema() : "default");
        List<Map<String, Object>> contentList = new ArrayList<>();

        for (String sourceTable : job.getSourceTables()) {
            String targetTableName = resolveTargetTableName(job, sourceTable);
            Map<String, Object> reader = buildReader(job, source, sourceDb, sourceTable);
            Map<String, Object> writer = buildWriter(job, targetTableName);
            Map<String, Object> content = new HashMap<>();
            content.put("reader", reader);
            content.put("writer", writer);
            contentList.add(content);
        }

        Map<String, Object> speed = new HashMap<>();
        speed.put("channel", DEFAULT_CHANNEL);
        // Sprint 3 AC-13：速率限流
        if (job.getRateLimitEnabled() != null && job.getRateLimitEnabled() == 1) {
            if (job.getReadRateLimitMbps() != null && job.getReadRateLimitMbps() > 0) {
                // Addax speed.byte 单位 Byte/s；Mbps -> Byte/s：* 1024 * 1024 / 8
                long bytePerSecond = job.getReadRateLimitMbps() * 1024L * 1024L / 8L;
                speed.put("byte", bytePerSecond);
            }
            if (job.getWriteRateLimitRowsPerSecond() != null && job.getWriteRateLimitRowsPerSecond() > 0) {
                speed.put("record", job.getWriteRateLimitRowsPerSecond());
            }
        }

        Map<String, Object> setting = new HashMap<>();
        setting.put("speed", speed);

        Map<String, Object> jobMap = new HashMap<>();
        jobMap.put("setting", setting);
        jobMap.put("content", contentList);

        Map<String, Object> root = new HashMap<>();
        root.put("job", jobMap);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            logger.error("序列化 Addax 任务配置失败: syncJobId={}", job.getId(), e);
            throw new BusinessException(ErrorCode.ADDAX_EXECUTION_FAILED, "生成 Addax 配置失败: " + e.getMessage());
        }
    }

    private String resolveTargetTableName(SyncJob job, String sourceTable) {
        if (StringUtils.hasText(job.getTargetTable())) {
            return job.getTargetTable();
        }
        String sourceDb = StringUtils.hasText(job.getSourceDatabase()) ? job.getSourceDatabase()
                : (StringUtils.hasText(job.getSourceSchema()) ? job.getSourceSchema() : "default");
        String db = sourceDb.replaceAll("[^a-zA-Z0-9_]", "_");
        String table = sourceTable.replaceAll("[^a-zA-Z0-9_]", "_");
        return "sync_" + db + "_" + table;
    }

    private Map<String, Object> buildReader(SyncJob job, DataSourceConnection source, String sourceDb, String sourceTable) {
        String jdbcUrl = connectionTester.buildJdbcUrl(source);
        List<String> columns = buildReaderColumns(job);
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
            String maxValue = queryIncrementalMaxValue(job, source, sourceTable);
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

    private String queryIncrementalMaxValue(SyncJob job, DataSourceConnection source, String sourceTable) {
        // 首次成功同步之前，直接全量拉取
        boolean hasSuccessHistory = syncJobHistoryMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SyncJobHistory>()
                        .eq("sync_job_id", job.getId())
                        .eq("status", ExecutionStatus.SUCCESS.getCode())
        ) > 0;
        if (!hasSuccessHistory) {
            return null;
        }

        IncrementalFieldTypeResolver.TypeCategory sourceCategory = incrementalFieldTypeResolver
                .resolveSourceFieldType(source, job, sourceTable, job.getIncrementalField());
        String targetTableName = resolveTargetTableName(job, sourceTable);
        String targetDb = resolveTargetDatabase(job);
        IncrementalFieldTypeResolver.TypeCategory targetCategory = incrementalFieldTypeResolver
                .resolveTargetFieldType(targetDb, targetTableName, job.getIncrementalField());

        if (!incrementalFieldTypeResolver.isComparable(sourceCategory, targetCategory)) {
            logger.warn("增量字段源端与目标端类型不一致或不是可比较类型，将按全量同步处理: syncJobId={}, field={}, sourceCategory={}, targetCategory={}",
                    job.getId(), job.getIncrementalField(), sourceCategory, targetCategory);
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
            logger.warn("查询目标表增量最大值失败，将按全量同步处理: syncJobId={}, table={}, field={}",
                    job.getId(), targetTableName, job.getIncrementalField(), e);
        }
        return null;
    }

    private Map<String, Object> buildWriter(SyncJob job, String targetTableName) {
        List<String> columns = buildWriterColumns(job);
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

    private String resolveTargetDatabase(SyncJob job) {
        if (!StringUtils.hasText(job.getTargetDatabase())) {
            throw new IllegalArgumentException("目标库名不能为空: syncJobId=" + job.getId());
        }
        return job.getTargetDatabase();
    }

    private List<String> buildReaderColumns(SyncJob job) {
        if (job.getFieldMapping() != null && !job.getFieldMapping().isEmpty()) {
            return job.getFieldMapping().stream()
                    .map(FieldMappingItem::getSourceColumn)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
        return List.of("*");
    }

    private List<String> buildWriterColumns(SyncJob job) {
        if (job.getFieldMapping() != null && !job.getFieldMapping().isEmpty()) {
            return job.getFieldMapping().stream()
                    .map(FieldMappingItem::getTargetColumn)
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

    private AddaxExecutionResult runAddax(Long syncJobId, Path jobFilePath, Path logFilePath,
                                          SyncJob job, DataSourceConnection source) {
        String addaxSh = Paths.get(addaxHome, "bin", "addax.sh").toString();
        List<String> command = List.of(addaxSh, jobFilePath.toString());
        logger.info("启动 Addax: syncJobId={}, command={}, workDir={}", syncJobId, command, addaxHome);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(addaxHome));
        processBuilder.redirectErrorStream(true);

        List<String> logLines = new ArrayList<>();
        try {
            Process process = processBuilder.start();
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
                logger.error("Addax 执行超时: syncJobId={}", syncJobId);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }
            String logContent = String.join("\n", logLines);
            Files.writeString(logFilePath, logContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Addax 日志已写入: syncJobId={}, path={}, exitCode={}", syncJobId, logFilePath, exitCode);

            AddaxLogParser.AddaxParseResult parseResult = addaxLogParser.parse(logLines);
            boolean success = exitCode == 0 && parseResult.errorRows() == 0 && parseResult.errorLines().isEmpty();
            return new AddaxExecutionResult(success,
                    parseResult.readRows(), parseResult.writeRows(), parseResult.errorRows(),
                    success ? null : buildErrorMessage(parseResult, exitCode),
                    logFilePath.toString(), logLines);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Addax 执行被中断: syncJobId={}", syncJobId, e);
            return new AddaxExecutionResult(false, 0, 0, 0,
                    "Addax 执行被中断: " + e.getMessage(), logFilePath.toString(), logLines);
        } catch (Exception e) {
            logger.error("Addax 执行异常: syncJobId={}", syncJobId, e);
            return new AddaxExecutionResult(false, 0, 0, 0,
                    "Addax 执行异常: " + e.getMessage(), logFilePath.toString(), logLines);
        }
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

    public record AddaxExecutionResult(boolean success, long readRows, long writeRows, long errorRows,
                                       String errorMessage, String logPath, List<String> logLines) {
    }
}
