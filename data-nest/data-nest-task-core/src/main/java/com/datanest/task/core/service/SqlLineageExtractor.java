package com.datanest.task.core.service;

import com.datanest.task.core.entity.LineageRecord;
import com.datanest.task.core.mapper.LineageRecordMapper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL 血缘提取器
 * 基于 JSqlParser 从 SQL 语句中提取源表与目标表，写入 lineage_record。
 */
@Service
public class SqlLineageExtractor {

    private static final Logger logger = LoggerFactory.getLogger(SqlLineageExtractor.class);

    private final LineageRecordMapper lineageRecordMapper;

    public SqlLineageExtractor(LineageRecordMapper lineageRecordMapper) {
        this.lineageRecordMapper = lineageRecordMapper;
    }

    /**
     * 解析 SQL 文本，提取表级血缘关系。
     *
     * @param sql         SQL 文本（支持多条语句，用 ; 分隔）
     * @param dagId       DAG ID
     * @param dagName     DAG 名称
     * @param nodeId      节点 ID
     * @param nodeName    节点名称
     * @param executionId 执行实例 ID
     */
    public void extract(String sql, Long dagId, String dagName, String nodeId, String nodeName, Long executionId) {
        if (!org.springframework.util.StringUtils.hasText(sql)) {
            return;
        }
        for (String stmt : splitStatements(sql)) {
            if (stmt.isBlank()) {
                continue;
            }
            try {
                Statement parsed = CCJSqlParserUtil.parse(stmt);
                extractSingle(parsed, dagId, dagName, nodeId, nodeName, executionId);
            } catch (Exception e) {
                logger.debug("SQL 血缘解析跳过单条语句: {}", stmt, e);
            }
        }
    }

    private void extractSingle(Statement stmt, Long dagId, String dagName, String nodeId,
                               String nodeName, Long executionId) {
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> allTables = finder.getTableList(stmt);
        if (allTables == null || allTables.isEmpty()) {
            return;
        }

        String targetTable = null;
        if (stmt instanceof CreateTable) {
            targetTable = tableNameOf(((CreateTable) stmt).getTable());
        } else if (stmt instanceof CreateView) {
            targetTable = tableNameOf(((CreateView) stmt).getView());
        } else if (stmt instanceof Insert) {
            targetTable = tableNameOf(((Insert) stmt).getTable());
        } else if (stmt instanceof Update) {
            targetTable = tableNameOf(((Update) stmt).getTable());
        } else if (stmt instanceof Delete) {
            targetTable = tableNameOf(((Delete) stmt).getTable());
        }

        Set<String> sourceTables = new HashSet<>(allTables);
        if (targetTable != null) {
            sourceTables.remove(targetTable);
        }

        List<LineageRecord> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (String source : sourceTables) {
            LineageRecord r = new LineageRecord();
            r.setSourceTable(normalizeTable(source));
            r.setTargetTable(normalizeTable(targetTable));
            r.setDagId(dagId);
            r.setDagName(dagName);
            r.setNodeId(nodeId);
            r.setNodeName(nodeName);
            r.setExecutionId(executionId);
            r.setLineageType("SQL");
            r.setCreatedAt(now);
            records.add(r);
        }
        if (records.isEmpty() && targetTable != null) {
            // 只有目标表、没有源表的情况（如 CREATE TABLE 无 CTAS）也记录一条
            LineageRecord r = new LineageRecord();
            r.setSourceTable(null);
            r.setTargetTable(normalizeTable(targetTable));
            r.setDagId(dagId);
            r.setDagName(dagName);
            r.setNodeId(nodeId);
            r.setNodeName(nodeName);
            r.setExecutionId(executionId);
            r.setLineageType("SQL");
            r.setCreatedAt(now);
            records.add(r);
        }
        saveRecords(records);
    }

    /**
     * 记录 Python 节点产出血缘。
     * Python 节点只声明输出表，源表由用户自行在脚本内管理，故 sourceTable 为空。
     */
    public void recordPythonLineage(List<String> outputTables, Long dagId, String dagName,
                                    String nodeId, String nodeName, Long executionId) {
        if (outputTables == null || outputTables.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<LineageRecord> records = new ArrayList<>(outputTables.size());
        for (String table : outputTables) {
            LineageRecord r = new LineageRecord();
            r.setSourceTable(null);
            r.setTargetTable(normalizeTable(table));
            r.setDagId(dagId);
            r.setDagName(dagName);
            r.setNodeId(nodeId);
            r.setNodeName(nodeName);
            r.setExecutionId(executionId);
            r.setLineageType("PYTHON");
            r.setCreatedAt(now);
            records.add(r);
        }
        saveRecords(records);
    }

    private void saveRecords(List<LineageRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (records.size() == 1) {
            lineageRecordMapper.insert(records.get(0));
        } else {
            lineageRecordMapper.insertBatch(records);
        }
    }

    private String tableNameOf(net.sf.jsqlparser.schema.Table table) {
        if (table == null) {
            return null;
        }
        if (table.getSchemaName() != null) {
            return table.getSchemaName() + "." + table.getName();
        }
        return table.getName();
    }

    private String normalizeTable(String table) {
        if (table == null) {
            return null;
        }
        String t = table.trim();
        // 去除 MySQL/Doris 的反引号
        if (t.startsWith("`") && t.endsWith("`") && t.length() > 1) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (c == ';' && !inString) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }
}
