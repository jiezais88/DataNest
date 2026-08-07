package com.datanest.task.core.service;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.governance.api.MetadataWriteApi;
import com.datanest.governance.api.dto.LineageRecordBatchRequest;
import com.datanest.governance.api.dto.LineageRecordItemDTO;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL 血缘提取器
 * 基于 JSqlParser 从 SQL 语句中提取源表与目标表，写入 lineage_record。
 * <p>
 * 微服务化 4.2：lineage_record 写入改为经 {@link MetadataWriteApi} Feign 调 app-governance
 * （lineage/records:batch，RemoteCalls 降级：血缘丢失不影响执行结果）；SQL 解析仍留在本地。
 */
@Service
public class SqlLineageExtractor {

    private static final Logger logger = LoggerFactory.getLogger(SqlLineageExtractor.class);

    private final MetadataWriteApi metadataWriteApi;

    public SqlLineageExtractor(MetadataWriteApi metadataWriteApi) {
        this.metadataWriteApi = metadataWriteApi;
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

        List<LineageRecordItemDTO> records = new ArrayList<>();
        for (String source : sourceTables) {
            LineageRecordItemDTO r = new LineageRecordItemDTO();
            r.setSourceTable(normalizeTable(source));
            r.setTargetTable(normalizeTable(targetTable));
            r.setDagId(dagId);
            r.setDagName(dagName);
            r.setNodeId(nodeId);
            r.setNodeName(nodeName);
            r.setExecutionId(executionId);
            r.setLineageType("SQL");
            records.add(r);
        }
        // Sprint 5：字段级血缘提取（仅覆盖直接列映射场景，见 ADR-S5-005）
        extractColumnMappings(stmt, targetTable, sourceTables, records, dagId, dagName, nodeId, nodeName,
                executionId);
        if (records.isEmpty() && targetTable != null) {
            // 只有目标表、没有源表的情况（如 CREATE TABLE 无 CTAS）也记录一条
            LineageRecordItemDTO r = new LineageRecordItemDTO();
            r.setSourceTable(null);
            r.setTargetTable(normalizeTable(targetTable));
            r.setDagId(dagId);
            r.setDagName(dagName);
            r.setNodeId(nodeId);
            r.setNodeName(nodeName);
            r.setExecutionId(executionId);
            r.setLineageType("SQL");
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
        List<LineageRecordItemDTO> records = new ArrayList<>(outputTables.size());
        for (String table : outputTables) {
            LineageRecordItemDTO r = new LineageRecordItemDTO();
            r.setSourceTable(null);
            r.setTargetTable(normalizeTable(table));
            r.setDagId(dagId);
            r.setDagName(dagName);
            r.setNodeId(nodeId);
            r.setNodeName(nodeName);
            r.setExecutionId(executionId);
            r.setLineageType("PYTHON");
            records.add(r);
        }
        saveRecords(records);
    }

    /**
     * Sprint 5：提取字段级血缘（source_column → target_column）。
     * 覆盖范围（ADR-S5-005）：
     *   - INSERT INTO t(a,b) SELECT x,y FROM s：直接列映射
     *   - CREATE TABLE t AS SELECT ...：CTAS 列映射
     * 规则：
     *   - 仅当语句只有一个源表时生成字段级记录（列归属确定，避免多表 JOIN 时列归属错误）；
     *     多源表语句仅保留表级血缘。
     *   - 复杂表达式（如 x+y）以表达式字符串作为 sourceColumn，便于前端提示。
     *   - 字段级记录与表级记录并存（表级血缘仍作为图谱锚点）。
     */
    private void extractColumnMappings(Statement stmt, String targetTable, Set<String> sourceTables,
                                       List<LineageRecordItemDTO> records, Long dagId, String dagName,
                                       String nodeId, String nodeName, Long executionId) {
        if (targetTable == null || sourceTables.size() != 1) {
            return;
        }
        Select select = null;
        List<String> declaredTargetColumns = null;
        if (stmt instanceof Insert insert) {
            select = insert.getSelect();
            if (insert.getColumns() != null && !insert.getColumns().isEmpty()) {
                declaredTargetColumns = insert.getColumns().stream()
                        .map(col -> col.getColumnName())
                        .toList();
            }
        } else if (stmt instanceof CreateTable createTable) {
            select = createTable.getSelect();
            if (createTable.getColumnDefinitions() != null && !createTable.getColumnDefinitions().isEmpty()) {
                declaredTargetColumns = createTable.getColumnDefinitions().stream()
                        .map(ColumnDefinition::getColumnName)
                        .toList();
            }
        }
        if (select == null || select.getPlainSelect() == null) {
            return;
        }
        PlainSelect plain = select.getPlainSelect();
        List<SelectItem<?>> selectItems = plain.getSelectItems();
        if (selectItems == null || selectItems.isEmpty()) {
            return;
        }

        String singleSource = sourceTables.iterator().next();
        for (int i = 0; i < selectItems.size(); i++) {
            SelectItem<?> item = selectItems.get(i);
            Expression expression = item.getExpression();
            String sourceColumn = columnNameOf(expression);
            String targetColumn = targetColumnAt(i, declaredTargetColumns, item);
            if (sourceColumn == null || targetColumn == null) {
                continue;
            }
            LineageRecordItemDTO r = new LineageRecordItemDTO();
            r.setSourceTable(normalizeTable(singleSource));
            r.setSourceColumn(sourceColumn);
            r.setTargetTable(normalizeTable(targetTable));
            r.setTargetColumn(targetColumn);
            r.setDagId(dagId);
            r.setDagName(dagName);
            r.setNodeId(nodeId);
            r.setNodeName(nodeName);
            r.setExecutionId(executionId);
            r.setLineageType("SQL");
            records.add(r);
        }
    }

    /**
     * 取 select 项的源列名：直接列引用取列名，复杂表达式取表达式原文。
     */
    private String columnNameOf(Expression expression) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof Column column) {
            return column.getColumnName();
        }
        return expression.toString();
    }

    /**
     * 计算目标列名：优先取 INSERT/CTAS 声明的目标列，否则回退 select 项的别名。
     */
    private String targetColumnAt(int index, List<String> declaredTargetColumns, SelectItem<?> item) {
        if (declaredTargetColumns != null && index < declaredTargetColumns.size()) {
            return declaredTargetColumns.get(index);
        }
        if (item.getAlias() != null && item.getAlias().getName() != null) {
            return item.getAlias().getName();
        }
        // 无别名且无声明列时无法确定目标列，跳过该字段级血缘
        return null;
    }

    /**
     * 血缘记录批量写入（RemoteCalls 降级：血缘丢失不影响执行结果；createdAt 由服务端填当前时间）。
     */
    private void saveRecords(List<LineageRecordItemDTO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        RemoteCalls.execute("governance.lineage.records-batch", () -> {
            LineageRecordBatchRequest request = new LineageRecordBatchRequest();
            request.setRecords(records);
            Result<Integer> result = metadataWriteApi.saveLineageRecords(request);
            if (result == null || result.data() == null || result.data() < records.size()) {
                logger.warn("血缘记录批量写入条数不符（降级/部分丢失）: expected={}, actual={}",
                        records.size(), result == null ? null : result.data());
            }
        });
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
