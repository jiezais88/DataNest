package com.datanest.dataservice.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.audit.AuditContent;
import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.audit.AuditLogRecorder;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.dataservice.dto.SqlCancelRequest;
import com.datanest.common.util.CsvExportHelper;
import com.datanest.common.util.XlsxExportHelper;
import com.datanest.dataservice.dto.SqlDatasourceDTO;
import com.datanest.dataservice.dto.SqlExecuteRequest;
import com.datanest.dataservice.dto.SqlExecuteResult;
import com.datanest.dataservice.dto.SqlExportRequest;
import com.datanest.dataservice.entity.SqlQueryHistory;
import com.datanest.dataservice.mapper.SqlQueryHistoryMapper;
import com.datanest.dataservice.service.SqlQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 查询终端（Sprint 10 F1，四角色 OR）。
 */
@Tag(name = "SQL 查询终端", description = "只读 SQL 执行 / 查询历史 / 数据源下拉")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
@RestController
@RequestMapping("/sql-console")
public class SqlQueryController {

    private final SqlQueryService sqlQueryService;
    private final SqlQueryHistoryMapper historyMapper;
    private final AuditLogRecorder auditLogRecorder;

    public SqlQueryController(SqlQueryService sqlQueryService, SqlQueryHistoryMapper historyMapper,
                              AuditLogRecorder auditLogRecorder) {
        this.sqlQueryService = sqlQueryService;
        this.historyMapper = historyMapper;
        this.auditLogRecorder = auditLogRecorder;
    }

    @Operation(summary = "执行只读 SQL", description = "JSqlParser 语法级只读校验（SELECT/WITH/SHOW/DESC/EXPLAIN），命中机密表拦截；请求带 queryId 时支持「停止」取消")
    @PostMapping("/execute")
    public Result<SqlExecuteResult> execute(@Valid @RequestBody SqlExecuteRequest request) {
        long start = System.currentTimeMillis();
        try {
            SqlExecuteResult result = sqlQueryService.execute(request);
            writeSqlAudit(request, result, null, start);
            return Result.ok(result);
        } catch (RuntimeException e) {
            writeSqlAudit(request, null, e, start);
            throw e;
        }
    }

    /** SQL 查询审计（AL-2/AL-3）：数据源名 + SQL 摘要 + 行数 + 耗时 + 失败原因，fail-open */
    private void writeSqlAudit(SqlExecuteRequest request, SqlExecuteResult result, RuntimeException error, long start) {
        try {
            String datasourceName = sqlQueryService.datasourceName(request.getDatasourceId());
            int durationMs = (int) (System.currentTimeMillis() - start);
            String content = AuditContent.sql(request.getSql(),
                    result == null ? null : result.getRowCount(), durationMs);
            Long operatorId = null;
            String operatorName = null;
            try {
                operatorId = StpUtil.getLoginIdAsLong();
                Object name = StpUtil.getSession().get("username");
                operatorName = name == null ? null : String.valueOf(name);
            } catch (Exception ex) {
                // 未登录/无 session，operator 信息留空由 system 回填
            }
            auditLogRecorder.record(new AuditLogEvent(
                    operatorId, operatorName,
                    AuditOpType.EXECUTE.name(), AuditResourceType.SQL_QUERY.name(),
                    String.valueOf(request.getDatasourceId()), datasourceName,
                    content,
                    error == null ? AuditLogEvent.RESULT_SUCCESS : AuditLogEvent.RESULT_FAILURE,
                    error == null ? null : truncate(error.getMessage(), 500), null));
        } catch (Exception e) {
            // fail-open：审计失败不影响 SQL 执行
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    @Operation(summary = "导出查询结果（XLSX/CSV，后端生成）", description = "复用 execute 全链路（只读校验+敏感度闸门+写历史）；文件名 = 数据源_表_时间戳，RFC5987 中文名")
    @PostMapping("/export")
    public void export(@Valid @RequestBody SqlExportRequest request, HttpServletResponse response) throws IOException {
        SqlExecuteResult result = sqlQueryService.export(request);
        String format = request.getFormat() == null ? "" : request.getFormat().toUpperCase();
        String base = buildExportBaseName(request.getDatasourceId(), request.getSql());
        if ("CSV".equals(format)) {
            response.setContentType("text/csv;charset=UTF-8");
            setContentDisposition(response, base + ".csv");
            CsvExportHelper.write(response.getOutputStream(), result.getColumns(), result.getRows());
        } else {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            setContentDisposition(response, base + ".xlsx");
            try (SXSSFWorkbook wb = XlsxExportHelper.workbook()) {
                SXSSFSheet sheet = wb.createSheet("查询结果");
                int[] widths = new int[result.getColumns().size()];
                XlsxExportHelper.writeHeaderRow(sheet, 0, result.getColumns(), widths);
                for (int i = 0; i < result.getRows().size(); i++) {
                    List<Object> row = new ArrayList<>(result.getColumns().size());
                    for (String col : result.getColumns()) {
                        row.add(result.getRows().get(i).get(col));
                    }
                    XlsxExportHelper.writeRow(sheet, i + 1, row, widths);
                }
                XlsxExportHelper.applyColumnWidths(sheet, widths);
                XlsxExportHelper.write(wb, response.getOutputStream());
            }
        }
    }

    /** 导出文件名：数据源名_表名_yyyyMMdd_HHmmss（表名从 SQL 提取，去注释；取不到用 result） */
    private String buildExportBaseName(Long datasourceId, String sql) {
        String dsName = sqlQueryService.datasourceName(datasourceId);
        String table = extractTableName(sql);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return dsName + "_" + table + "_" + now;
    }

    /** 从 SQL 提取第一个表名（对齐前端 extractTableName：先剥离注释避免误匹配，取不到用 result） */
    private String extractTableName(String sql) {
        if (sql == null) {
            return "result";
        }
        String withoutComments = sql.replaceAll("/\\*[\\s\\S]*?\\*/", " ").replaceAll("--.*$", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(?:FROM|JOIN|INTO|UPDATE)\\s+([`\"]?[A-Za-z0-9_.]+[`\"]?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(withoutComments);
        return m.find() ? m.group(1).replaceAll("[`\"]", "") : "result";
    }

    /** Content-Disposition：ASCII 兜底名 + RFC5987 中文名 */
    private void setContentDisposition(HttpServletResponse response, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"result\"; filename*=UTF-8''" + encoded);
    }

    @Operation(summary = "停止查询", description = "按执行时下发的 queryId 取消本次查询（中断线程 + 关闭连接）；查无此 id 幂等返回")
    @PostMapping("/cancel")
    public Result<Boolean> cancel(@Valid @RequestBody SqlCancelRequest request) {
        return Result.ok(sqlQueryService.cancel(request.getQueryId()));
    }

    @Operation(summary = "数据源下拉", description = "内置 Doris + 状态 NORMAL 的平台数据源")
    @GetMapping("/datasources")
    public Result<List<SqlDatasourceDTO>> listDatasources() {
        return Result.ok(sqlQueryService.listQueryableDatasources());
    }

    @Operation(summary = "我的查询历史（分页）")
    @GetMapping("/history")
    public Result<PageResult<SqlQueryHistory>> history(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "20") long pageSize) {
        long userId = StpUtil.getLoginIdAsLong();
        Page<SqlQueryHistory> p = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        QueryWrapper<SqlQueryHistory> wrapper = new QueryWrapper<SqlQueryHistory>()
                .eq("user_id", userId)
                .orderByDesc("created_at");
        Page<SqlQueryHistory> result = historyMapper.selectPage(p, wrapper);
        return Result.ok(PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize()));
    }

    @Operation(summary = "清空我的查询历史")
    @DeleteMapping("/history")
    public Result<Void> clearHistory() {
        long userId = StpUtil.getLoginIdAsLong();
        historyMapper.delete(new QueryWrapper<SqlQueryHistory>().eq("user_id", userId));
        return Result.ok(null);
    }
}
