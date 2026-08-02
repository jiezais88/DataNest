package com.datanest.task.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.datanest.task.core.config.DorisDataSourceConfig;
import com.datanest.task.core.dto.PythonExecuteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Python 节点执行器（沙箱化）
 * - 在独立临时目录中运行用户脚本
 * - 注入 Doris 连接、参数获取、日志、读写表等 helper
 * - 顶部注入黑名单，限制危险调用
 */
@Service
public class PythonExecutor {

    private static final Logger logger = LoggerFactory.getLogger(PythonExecutor.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30 * 60;
    private static final int DEFAULT_MEMORY_LIMIT_MB = 2048;

    @Value("${datanest.python.sandbox:/tmp/datanest-python-sandbox}")
    private String sandboxBase;

    /**
     * 执行用户 Python 脚本。
     *
     * @param userScript    用户脚本（在 helper 之后执行）
     * @param context       上下文：参数、日志回调等
     * @param timeoutSeconds 超时秒数，null 则默认 30 分钟
     * @param memoryLimitMb 内存限制（MB），通过 ulimit -v 限制虚拟内存，默认 2048MB
     */
    public PythonExecuteResult execute(String userScript, PythonContext context,
                                       Integer timeoutSeconds, Integer memoryLimitMb) {
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        int memory = memoryLimitMb == null || memoryLimitMb <= 0 ? DEFAULT_MEMORY_LIMIT_MB : memoryLimitMb;
        LocalDateTime startTime = LocalDateTime.now();
        Path workDir = null;
        try {
            workDir = createWorkDir();
            DorisConfig doris = resolveDorisConfig();
            writeDorisConfig(workDir, doris);
            writeTaskScript(workDir, userScript, context);
            return runPython(workDir, timeout, memory, context, startTime);
        } catch (Exception e) {
            logger.error("Python 执行器异常", e);
            long durationMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            return PythonExecuteResult.failure("", e.getMessage(), e.getMessage(), durationMs);
        } finally {
            if (workDir != null) {
                cleanupQuietly(workDir);
            }
        }
    }

    private Path createWorkDir() throws IOException {
        Path base = Path.of(sandboxBase);
        Files.createDirectories(base);
        Path dir = Files.createTempDirectory(base, "py-" + UUID.randomUUID().toString().substring(0, 8) + "-");
        logger.debug("Python 沙箱工作目录: {}", dir);
        return dir;
    }

    private void cleanupQuietly(Path workDir) {
        try {
            Files.walk(workDir)
                    .sorted(Collections.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                        }
                    });
        } catch (Exception e) {
            logger.warn("清理 Python 工作目录失败: {}", workDir, e);
        }
    }

    private DorisConfig resolveDorisConfig() {
        // 优先读 DorisDataSourceConfig 静态配置；连接池未初始化时降级 system property / 环境变量
        String host = System.getProperty("datanest.doris.fe-host", "localhost");
        String portStr = System.getProperty("datanest.doris.fe-query-port", "9030");
        String user = System.getProperty("datanest.doris.user", "root");
        String password = System.getProperty("datanest.doris.password", "");
        String database = System.getProperty("datanest.engineering.addax.target-database", "datanest");

        DataSource ds = DorisDataSourceConfig.getDataSource();
        if (ds != null) {
            try (Connection conn = ds.getConnection()) {
                // 从 JDBC URL 中解析 host/port/database
                String url = conn.getMetaData().getURL();
                JdbcUrlParts parts = parseJdbcUrl(url);
                if (parts.host != null) host = parts.host;
                if (parts.port != null) portStr = parts.port;
                if (parts.database != null) database = parts.database;
            } catch (SQLException e) {
                logger.warn("从 DorisDataSourceConfig 解析连接信息失败，降级到 system property", e);
            }
        }
        return new DorisConfig(host, Integer.parseInt(portStr), user, password, database);
    }

    private JdbcUrlParts parseJdbcUrl(String url) {
        JdbcUrlParts parts = new JdbcUrlParts();
        if (!StringUtils.hasText(url)) return parts;
        try {
            // jdbc:mysql://host:port/db?...
            int hostStart = url.indexOf("//");
            if (hostStart < 0) return parts;
            String remainder = url.substring(hostStart + 2);
            int slash = remainder.indexOf('/');
            String hostPort = slash < 0 ? remainder : remainder.substring(0, slash);
            int colon = hostPort.indexOf(':');
            if (colon > 0) {
                parts.host = hostPort.substring(0, colon);
                parts.port = hostPort.substring(colon + 1);
            } else {
                parts.host = hostPort;
            }
            if (slash >= 0) {
                String dbPart = remainder.substring(slash + 1);
                int q = dbPart.indexOf('?');
                parts.database = q < 0 ? dbPart : dbPart.substring(0, q);
            }
        } catch (Exception e) {
            logger.warn("解析 Doris JDBC URL 失败: {}", url, e);
        }
        return parts;
    }

    private static class JdbcUrlParts {
        String host;
        String port;
        String database;
    }

    private void writeDorisConfig(Path workDir, DorisConfig cfg) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("host", cfg.host);
        map.put("port", cfg.port);
        map.put("user", cfg.user);
        map.put("password", cfg.password);
        map.put("database", cfg.database);
        Files.writeString(workDir.resolve("doris.json"), JSON.toJSONString(map), StandardCharsets.UTF_8);
    }

    private void writeTaskScript(Path workDir, String userScript, PythonContext context) throws IOException {
        StringBuilder sb = new StringBuilder();

        // 安全黑名单
        sb.append("""
                import sys
                import os
                import importlib
                import builtins
                
                _FORBIDDEN_MODULES = {'os', 'subprocess', 'socket', 'urllib', 'urllib2', 'http', 'ftplib', 'telnetlib', 'ssl', 'smtplib', 'poplib', 'imaplib', 'nntplib'}
                _ORIGINAL_IMPORT = builtins.__import__
                
                def _safe_import(name, *args, **kwargs):
                    base = name.split('.')[0]
                    if base in _FORBIDDEN_MODULES:
                        raise ImportError(f"模块 {name} 已被沙箱禁用")
                    return _ORIGINAL_IMPORT(name, *args, **kwargs)
                
                builtins.__import__ = _safe_import
                
                # 禁止 os.system / os.popen / subprocess.Popen
                if 'os' in sys.modules:
                    sys.modules['os'].system = lambda *a, **k: (_ for _ in ()).throw(OSError('os.system 被禁用'))
                    sys.modules['os'].popen = lambda *a, **k: (_ for _ in ()).throw(OSError('os.popen 被禁用'))
                if 'subprocess' in sys.modules:
                    sys.modules['subprocess'].Popen = lambda *a, **k: (_ for _ in ()).throw(OSError('subprocess.Popen 被禁用'))
                
                """);

        // 参数上下文
        String paramsJson = context != null && context.params != null
                ? JSON.toJSONString(context.params)
                : "{}";
        sb.append("_PARAMS = ").append(paramsJson).append("\n");
        sb.append("_OUTPUT_TABLES = []\n");

        // Helper 函数
        sb.append("""
                import json
                import datetime
                
                def get_param(name, default=None):
                    return _PARAMS.get(name, default)
                
                def log(message):
                    line = f"[PYTHON-LOG] {message}"
                    print(line, flush=True)
                
                def _read_doris_config():
                    with open('doris.json', 'r', encoding='utf-8') as f:
                        return json.load(f)
                
                def _connect_doris(cfg):
                    try:
                        import pymysql
                    except ImportError:
                        raise ImportError('未安装 pymysql，请在镜像中安装：pip install pymysql')
                    return pymysql.connect(
                        host=cfg['host'],
                        port=int(cfg['port']),
                        user=cfg['user'],
                        password=cfg['password'],
                        database=cfg['database'],
                        charset='utf8mb4',
                        cursorclass=pymysql.cursors.DictCursor
                    )
                
                def _split_table_name(table):
                    cfg = _read_doris_config()
                    parts = table.split('.')
                    if len(parts) == 2:
                        return parts[0], parts[1]
                    return cfg['database'], parts[0]
                
                def read_doris_table(table):
                    import pandas as pd
                    cfg = _read_doris_config()
                    db, tbl = _split_table_name(table)
                    conn = _connect_doris(cfg)
                    try:
                        with conn.cursor() as cur:
                            cur.execute(f"SELECT * FROM `{db}`.`{tbl}`")
                            rows = cur.fetchall()
                            # Sprint 4：对齐 PRD，返回 pandas DataFrame，便于用户做 DataFrame 操作
                            if rows:
                                return pd.DataFrame(rows)
                            return pd.DataFrame()
                    finally:
                        conn.close()
                
                def write_doris_table(df, table):
                    import json as _json
                    cfg = _read_doris_config()
                    db, tbl = _split_table_name(table)
                    conn = _connect_doris(cfg)
                    try:
                        with conn.cursor() as cur:
                            # 统一把 DataFrame 转成 list[dict]
                            if hasattr(df, 'to_dict'):
                                records = df.to_dict(orient='records')
                            elif isinstance(df, list):
                                records = df
                            else:
                                records = []
                            if not records:
                                cur.execute(f"TRUNCATE TABLE `{db}`.`{tbl}`")
                                conn.commit()
                                return 0
                            columns = list(records[0].keys())
                            col_sql = ', '.join([f'`{c}`' for c in columns])
                            placeholders = ', '.join(['%s'] * len(columns))
                            sql = f"INSERT INTO `{db}`.`{tbl}` ({col_sql}) VALUES ({placeholders})"
                            rows = []
                            for row in records:
                                rows.append(tuple(row.get(c) for c in columns))
                            cur.executemany(sql, rows)
                            conn.commit()
                            _OUTPUT_TABLES.append(table)
                            return len(rows)
                    finally:
                        conn.close()
                
                """);

        // 注入用户脚本
        sb.append("\n# ===== USER SCRIPT =====\n");
        sb.append(userScript);
        sb.append("\n\n# ===== OUTPUT =====\n");
        sb.append("""
                _output = {}
                try:
                    import pandas as pd
                    _output['pandas_available'] = True
                except Exception:
                    _output['pandas_available'] = False
                try:
                    _output['output_tables'] = _OUTPUT_TABLES
                except Exception as e:
                    _output['output_tables_error'] = str(e)
                with open('output.json', 'w', encoding='utf-8') as f:
                    json.dump(_output, f, ensure_ascii=False, default=str)
                """);

        Files.writeString(workDir.resolve("task.py"), sb.toString(), StandardCharsets.UTF_8);
    }

    private PythonExecuteResult runPython(Path workDir, int timeoutSeconds, int memoryLimitMb,
                                          PythonContext context, LocalDateTime startTime) {
        // 通过 /bin/sh 设置 ulimit -v 限制虚拟内存（KB），再 exec python3 避免额外 shell 进程
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add("-c");
        command.add("ulimit -v " + (memoryLimitMb * 1024) + "; exec python3 task.py");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Process process = null;
        try {
            process = pb.start();

            // 在后台线程并发读取 stdout/stderr，避免脚本无输出时主线程阻塞在 readLine
            Process finalProcess = process;
            Thread outThread = new Thread(() -> pumpReader(
                    new BufferedReader(new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8)),
                    stdout, context, false), "python-stdout-pump");
            Thread errThread = new Thread(() -> pumpReader(
                    new BufferedReader(new InputStreamReader(finalProcess.getErrorStream(), StandardCharsets.UTF_8)),
                    stderr, context, true), "python-stderr-pump");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long durationMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

            // 等待流泵线程在进程结束后把数据读完（或超时后强制结束）
            if (!finished) {
                process.destroyForcibly();
                logger.error("Python 执行超时: timeout={}s", timeoutSeconds);
                // 给泵线程一点时间收尾，避免丢失已输出内容
                joinQuietly(outThread, 1000);
                joinQuietly(errThread, 1000);
                return PythonExecuteResult.timeout();
            }

            joinQuietly(outThread, 5000);
            joinQuietly(errThread, 5000);

            int exitCode = process.exitValue();
            Object output = null;
            List<String> outputTables = Collections.emptyList();
            Path outputFile = workDir.resolve("output.json");
            if (Files.exists(outputFile)) {
                try {
                    String content = Files.readString(outputFile, StandardCharsets.UTF_8);
                    JSONObject json = JSON.parseObject(content);
                    output = json;
                    outputTables = json.getList("output_tables", String.class);
                    if (outputTables == null) outputTables = Collections.emptyList();
                } catch (Exception e) {
                    logger.warn("解析 output.json 失败", e);
                }
            }

            if (exitCode == 0) {
                return PythonExecuteResult.success(
                        stdout.toString(), stderr.toString(), output, outputTables, durationMs);
            } else {
                String err = stderr.toString();
                if (!StringUtils.hasText(err)) err = "Python 退出码: " + exitCode;
                return PythonExecuteResult.failure(stdout.toString(), stderr.toString(), err, durationMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            long durationMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            return PythonExecuteResult.failure(stdout.toString(), stderr.toString(), "执行被中断", durationMs);
        } catch (IOException e) {
            if (process != null) process.destroyForcibly();
            long durationMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            return PythonExecuteResult.failure(stdout.toString(), stderr.toString(), e.getMessage(), durationMs);
        }
    }

    private void pumpReader(BufferedReader reader, StringBuilder sink,
                            PythonContext context, boolean isStderr) {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append("\n");
                }
                if (context != null && context.logCollector != null) {
                    context.logCollector.accept(isStderr ? "[STDERR] " + line : line);
                }
            }
        } catch (IOException e) {
            // 进程被销毁时流关闭会抛异常，正常忽略
            if (!"Stream closed".equalsIgnoreCase(e.getMessage())) {
                logger.debug("Python 流读取异常: {}", e.getMessage());
            }
        }
    }

    private void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record DorisConfig(String host, int port, String user, String password, String database) {
    }

    /**
     * Python 执行上下文
     */
    public static class PythonContext {
        private final Map<String, Object> params;
        private final java.util.function.Consumer<String> logCollector;

        public PythonContext(Map<String, Object> params, java.util.function.Consumer<String> logCollector) {
            this.params = params != null ? params : Collections.emptyMap();
            this.logCollector = logCollector;
        }

        public Map<String, Object> getParams() {
            return params;
        }
    }
}
