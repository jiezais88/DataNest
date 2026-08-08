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
        return doExecute(userScript, context, timeoutSeconds, memoryLimitMb, null, null);
    }

    /**
     * 执行质量检查 Python 脚本（Sprint 7 DG-10，方案 B 通用连接注入）。
     * 与 DAG Python 节点的差异：
     * <ul>
     *   <li>除 doris.json 外注入通用连接 conn.json（{@link PythonConnectionResolver} 解析），
     *       沙箱内 read_table(table, where=None, limit=None) 按数据源 type 选驱动拉取 DataFrame</li>
     *   <li>用户脚本只需定义 {@code def check(df)}；收尾自动以目标表 DataFrame 调用 check，
     *       返回 dict 写入 output.json 的 check_result（脚本内也可自行 read_table 采样后忽略入参 df）</li>
     * </ul>
     *
     * @param conn        通用连接信息（type/host/port/user/password/database/schema）
     * @param targetTable 目标表全名（db.table / schema.table，由调用方拼接）
     */
    public PythonExecuteResult executeQualityCheck(String userScript, Map<String, Object> conn,
                                                   String targetTable, PythonContext context,
                                                   Integer timeoutSeconds, Integer memoryLimitMb) {
        if (conn == null || conn.isEmpty()) {
            return PythonExecuteResult.failure("", "", "质量检查缺少目标数据源连接信息", 0L);
        }
        if (!StringUtils.hasText(targetTable)) {
            return PythonExecuteResult.failure("", "", "质量检查缺少目标表", 0L);
        }
        return doExecute(userScript, context, timeoutSeconds, memoryLimitMb, conn, targetTable);
    }

    private PythonExecuteResult doExecute(String userScript, PythonContext context,
                                          Integer timeoutSeconds, Integer memoryLimitMb,
                                          Map<String, Object> conn, String targetTable) {
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        int memory = memoryLimitMb == null || memoryLimitMb <= 0 ? DEFAULT_MEMORY_LIMIT_MB : memoryLimitMb;
        LocalDateTime startTime = LocalDateTime.now();
        Path workDir = null;
        try {
            workDir = createWorkDir();
            DorisConfig doris = resolveDorisConfig();
            writeDorisConfig(workDir, doris);
            if (conn != null) {
                Files.writeString(workDir.resolve("conn.json"), JSON.toJSONString(conn), StandardCharsets.UTF_8);
            }
            writeTaskScript(workDir, userScript, context, conn != null, targetTable);
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
        // 取 DorisDataSourceConfig 静态配置（@Value 注入值）；连接池未初始化时仅 host/port/database 从 URL 细化
        String host = DorisDataSourceConfig.currentHost() != null ? DorisDataSourceConfig.currentHost()
                : System.getProperty("datanest.doris.fe-host", "localhost");
        String portStr = DorisDataSourceConfig.currentPort() > 0 ? String.valueOf(DorisDataSourceConfig.currentPort())
                : System.getProperty("datanest.doris.fe-query-port", "9030");
        String user = DorisDataSourceConfig.currentUser() != null ? DorisDataSourceConfig.currentUser()
                : System.getProperty("datanest.doris.user", "root");
        String password = DorisDataSourceConfig.currentPassword() != null ? DorisDataSourceConfig.currentPassword()
                : System.getProperty("datanest.doris.password", "");
        String database = DorisDataSourceConfig.currentDatabase() != null ? DorisDataSourceConfig.currentDatabase()
                : System.getProperty("datanest.engineering.addax.target-database", "datanest");

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

    private void writeTaskScript(Path workDir, String userScript, PythonContext context,
                                 boolean qualityMode, String targetTable) throws IOException {
        StringBuilder sb = new StringBuilder();

        // 安全沙箱：允许 import os（pandas/numpy 依赖），但禁掉 os 中所有危险方法
        sb.append("""
                import sys
                import os
                import importlib
                import builtins
                
                """);
        // 质量检查模式（Sprint 7 DG-10）：read_table 需要 DB 驱动建连，pymysql/psycopg2 依赖 socket，
        // 故质量模式放开 socket（脚本由治理员/超管配置，连库是本职）；DAG 节点保持禁 socket 原状
        if (qualityMode) {
            sb.append("_FORBIDDEN_MODULES = {'urllib2', 'http', 'ftplib', 'telnetlib', 'ssl', 'smtplib', 'poplib', 'imaplib', 'nntplib'}\n");
        } else {
            sb.append("_FORBIDDEN_MODULES = {'socket', 'urllib2', 'http', 'ftplib', 'telnetlib', 'ssl', 'smtplib', 'poplib', 'imaplib', 'nntplib'}\n");
        }
        sb.append("""
                # urllib.parse 被 pathlib 等标准库安全使用，只允许 parse 子模块
                _FORBIDDEN_SUBMODULES = {'urllib.request', 'urllib.error', 'urllib.robotparser'}
                _ORIGINAL_IMPORT = builtins.__import__
                
                def _safe_import(name, *args, **kwargs):
                    base = name.split('.')[0]
                    if base in _FORBIDDEN_MODULES:
                        raise ImportError(f"模块 {name} 已被沙箱禁用")
                    if name in _FORBIDDEN_SUBMODULES or name.startswith('urllib.request.') or name.startswith('urllib.error.'):
                        raise ImportError(f"模块 {name} 已被沙箱禁用")
                    return _ORIGINAL_IMPORT(name, *args, **kwargs)
                
                builtins.__import__ = _safe_import
                
                # 即使 urllib.request 被放行，也禁掉网络请求方法
                try:
                    import urllib.request as _urllib_req
                    _URLOPEN_DANGEROUS = ['urlopen', 'urlretrieve', 'URLopener', 'FancyURLopener']
                    for _m in _URLOPEN_DANGEROUS:
                        if hasattr(_urllib_req, _m):
                            setattr(_urllib_req, _m, _forbidden_os_method(f'urllib.request.{_m}'))
                except Exception:
                    pass
                
                def _forbidden_os_method(name):
                    def _wrapper(*a, **k):
                        raise OSError(f'os.{name} 被沙箱禁用')
                    return _wrapper
                
                # pandas/numpy 需要 os 模块，但禁止危险操作
                _OS_DANGEROUS = [
                    'system', 'popen', 'spawnl', 'spawnle', 'spawnlp', 'spawnlpe',
                    'spawnv', 'spawnve', 'spawnvp', 'spawnvpe', 'execv', 'execve',
                    'execvp', 'execvpe', 'execl', 'execle', 'execlp', 'execlpe',
                    'remove', 'unlink', 'rmdir', 'removedirs', 'rename', 'renames',
                    'replace', 'mkdir', 'makedirs', 'chdir', 'fchdir', 'chroot',
                    'chmod', 'chown', 'lchown', 'link', 'symlink', 'kill', 'killpg',
                    'abort', 'fork', 'forkpty', 'wait', 'waitpid', 'wait3', 'wait4',
                    'setegid', 'seteuid', 'setgid', 'setgroups',
                    'setpgrp', 'setpgid', 'setpriority', 'setregid', 'setresgid',
                    'setresuid', 'setreuid', 'setsid', 'setuid',
                ]
                for _m in _OS_DANGEROUS:
                    if hasattr(os, _m):
                        setattr(os, _m, _forbidden_os_method(_m))
                
                # 允许 import subprocess（pandas 内部依赖），但禁止启动子进程
                _SUBPROCESS_DANGEROUS = ['Popen', 'run', 'call', 'check_call', 'check_output']
                try:
                    import subprocess as _subp
                    for _m in _SUBPROCESS_DANGEROUS:
                        if hasattr(_subp, _m):
                            setattr(_subp, _m, _forbidden_os_method(f'subprocess.{_m}'))
                except Exception:
                    pass
                
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

        // Sprint 7 DG-10：质量检查模式的通用连接读取 helper（conn.json，方案 B）
        if (qualityMode) {
            sb.append("""
                    import re as _re
                    
                    def _read_conn_config():
                        with open('conn.json', 'r', encoding='utf-8') as f:
                            return json.load(f)
                    
                    def _quote_ident(name, quote):
                        if not _re.fullmatch(r'[A-Za-z0-9_]+', name or ''):
                            raise ValueError(f'非法表名标识: {name}')
                        return f'{quote}{name}{quote}' if quote else name
                    
                    def _connect_any(cfg):
                        t = (cfg.get('type') or '').lower()
                        if t in ('mysql', 'doris'):
                            try:
                                import pymysql
                            except ImportError:
                                raise ImportError('未安装 pymysql，请在镜像中安装：pip install pymysql')
                            return pymysql.connect(
                                host=cfg['host'], port=int(cfg['port']), user=cfg['user'],
                                password=cfg['password'], database=cfg.get('database') or None,
                                charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor), '`'
                        if t in ('postgresql', 'postgres', 'pg'):
                            try:
                                import psycopg2
                                import psycopg2.extras
                            except ImportError:
                                raise ImportError('未安装 psycopg2，请在镜像中安装：pip install psycopg2-binary')
                            return psycopg2.connect(
                                host=cfg['host'], port=int(cfg['port']), user=cfg['user'],
                                password=cfg['password'], dbname=cfg.get('database') or None,
                                cursor_factory=psycopg2.extras.RealDictCursor), '"'
                        if t == 'oracle':
                            try:
                                import oracledb
                            except ImportError:
                                raise ImportError('未安装 oracledb，请在镜像中安装：pip install oracledb')
                            # thin 模式无需 Oracle client；database 字段按 service_name 连接
                            return oracledb.connect(
                                user=cfg['user'], password=cfg['password'],
                                host=cfg['host'], port=int(cfg['port']),
                                service_name=cfg.get('database')), None
                        raise ValueError(f'read_table 暂不支持的数据源类型: {t}')
                    
                    def read_table(table, where=None, limit=None):
                        \"\"\"按 conn.json 连接信息拉取表数据为 DataFrame。
                        table 支持 'db.table'（MySQL/Doris）/ 'schema.table'（PG/Oracle）两级限定；
                        where/limit 自由控制采样（质量脚本不受 SQL 预览的行数/超时限制）。\"\"\"
                        import pandas as pd
                        cfg = _read_conn_config()
                        t = (cfg.get('type') or '').lower()
                        conn, quote = _connect_any(cfg)
                        try:
                            parts = table.split('.')
                            if len(parts) == 2:
                                ns, tbl = parts
                            else:
                                ns = cfg.get('schema') if t in ('postgresql', 'postgres', 'pg', 'oracle') else cfg.get('database')
                                tbl = parts[0]
                            full = f'{_quote_ident(ns, quote)}.{_quote_ident(tbl, quote)}' if ns else _quote_ident(tbl, quote)
                            sql = f'SELECT * FROM {full}'
                            if where:
                                sql += f' WHERE {where}'
                            if limit is not None:
                                if t == 'oracle':
                                    sql += f' FETCH FIRST {int(limit)} ROWS ONLY'
                                else:
                                    sql += f' LIMIT {int(limit)}'
                            cur = conn.cursor()
                            try:
                                cur.execute(sql)
                                rows = cur.fetchall()
                                cols = [d[0] for d in cur.description] if cur.description else []
                            finally:
                                cur.close()
                            if not rows:
                                return pd.DataFrame()
                            if isinstance(rows[0], dict):
                                return pd.DataFrame(rows)
                            return pd.DataFrame(rows, columns=cols)
                        finally:
                            conn.close()
                    
                    """);
        }

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
                """);
        // 质量检查模式收尾：以目标表 DataFrame 调 check(df)，返回 dict 写 output.json
        if (qualityMode) {
            sb.append("_quality_target = ").append(JSON.toJSONString(targetTable)).append("\n");
            sb.append("""
                    _check_df = read_table(_quality_target)
                    _output['check_result'] = check(_check_df)
                    """);
        }
        sb.append("""
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
