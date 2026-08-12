package com.datanest.dataservice.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.dataservice.dto.SqlCancelRequest;
import com.datanest.dataservice.dto.SqlDatasourceDTO;
import com.datanest.dataservice.dto.SqlExecuteRequest;
import com.datanest.dataservice.dto.SqlExecuteResult;
import com.datanest.dataservice.entity.SqlQueryHistory;
import com.datanest.dataservice.mapper.SqlQueryHistoryMapper;
import com.datanest.dataservice.service.SqlQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public SqlQueryController(SqlQueryService sqlQueryService, SqlQueryHistoryMapper historyMapper) {
        this.sqlQueryService = sqlQueryService;
        this.historyMapper = historyMapper;
    }

    @Operation(summary = "执行只读 SQL", description = "JSqlParser 语法级只读校验（SELECT/WITH/SHOW/DESC/EXPLAIN），命中机密表拦截；请求带 queryId 时支持「停止」取消")
    @PostMapping("/execute")
    public Result<SqlExecuteResult> execute(@Valid @RequestBody SqlExecuteRequest request) {
        return Result.ok(sqlQueryService.execute(request));
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
