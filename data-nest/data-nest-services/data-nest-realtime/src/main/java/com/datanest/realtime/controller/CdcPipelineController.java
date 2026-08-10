package com.datanest.realtime.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.realtime.dto.CdcPipelineDTO;
import com.datanest.realtime.dto.CdcPipelineLogDTO;
import com.datanest.realtime.dto.CdcPipelineSaveRequest;
import com.datanest.realtime.dto.CdcPipelineStatsDTO;
import com.datanest.realtime.dto.CdcSourceTableDTO;
import com.datanest.realtime.dto.CdcSourceValidateRequest;
import com.datanest.realtime.dto.CdcSourceValidateResult;
import com.datanest.realtime.service.CdcPipelineService;
import com.datanest.realtime.service.SourcePrecheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CDC 实时同步管道 Controller（Sprint 8 F2）。
 * <p>
 * 读接口四角色可见；写接口（增删改/启停/预检/刷 catalog）仅超管与数据工程师。
 */
@Tag(name = "实时 CDC 管道", description = "CDC 管道 CRUD / 启停（savepoint 恢复）/ 源预检 / 运行日志 / Doris catalog 刷新")
@RestController
@RequestMapping("/cdc/pipelines")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class CdcPipelineController {

    private final CdcPipelineService pipelineService;
    private final SourcePrecheckService precheckService;

    public CdcPipelineController(CdcPipelineService pipelineService,
                                 SourcePrecheckService precheckService) {
        this.pipelineService = pipelineService;
        this.precheckService = precheckService;
    }

    @Operation(summary = "源数据源预检", description = "连通性 / binlog 开启 / binlog_format=ROW / 源库存在性逐项检查")
    @PostMapping("/validate-source")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<CdcSourceValidateResult> validateSource(@RequestBody CdcSourceValidateRequest request) {
        return Result.ok(precheckService.validate(request.getDatasourceId(), request.getSourceDatabase()));
    }

    @Operation(summary = "源数据源库列表", description = "列出源 MySQL 的全部业务库（过滤系统库），供建管道选库")
    @GetMapping("/source-databases/{datasourceId}")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<List<String>> listSourceDatabases(@Parameter(description = "源数据源 ID") @PathVariable Long datasourceId) {
        return Result.ok(precheckService.listDatabases(datasourceId));
    }

    @Operation(summary = "源库表列表", description = "列出源库下全部业务表（表名 + 约估行数），供建管道勾选同步表")
    @GetMapping("/source-tables/{datasourceId}")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<List<CdcSourceTableDTO>> listSourceTables(@Parameter(description = "源数据源 ID") @PathVariable Long datasourceId,
                                                            @Parameter(description = "源库名") @RequestParam String database) {
        return Result.ok(precheckService.listTables(datasourceId, database));
    }

    @Operation(summary = "创建管道", description = "初始状态 STOPPED；UPSERT 模式每表必须配置主键")
    @PostMapping
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<CdcPipelineDTO> create(@RequestBody CdcPipelineSaveRequest request) {
        return Result.ok(pipelineService.create(request));
    }

    @Operation(summary = "管道统计", description = "运行中/已停止/异常计数 + 已同步表总数（列表页顶部统计卡）")
    @GetMapping("/stats")
    public Result<CdcPipelineStatsDTO> stats() {
        return Result.ok(pipelineService.stats());
    }

    @Operation(summary = "管道分页", description = "按状态/名称关键字过滤，id 倒序")
    @GetMapping("/page")
    public Result<PageResult<CdcPipelineDTO>> page(@Parameter(description = "状态（STOPPED/RUNNING/ERROR）") @RequestParam(required = false) String status,
                                                   @Parameter(description = "名称关键字") @RequestParam(required = false) String keyword,
                                                   @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
                                                   @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(pipelineService.page(status, keyword, page, pageSize));
    }

    @Operation(summary = "管道详情", description = "含表级映射与源数据源名")
    @GetMapping("/{id}")
    public Result<CdcPipelineDTO> detail(@Parameter(description = "管道 ID") @PathVariable Long id) {
        return Result.ok(pipelineService.detail(id));
    }

    @Operation(summary = "编辑管道", description = "仅停止状态可编辑；全量替换表映射并清空 savepoint（下次启动从头跑）")
    @PutMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<CdcPipelineDTO> update(@Parameter(description = "管道 ID") @PathVariable Long id,
                                         @RequestBody CdcPipelineSaveRequest request) {
        return Result.ok(pipelineService.update(id, request));
    }

    @Operation(summary = "删除管道", description = "运行中禁止删除；级联删除表映射与运行日志")
    @DeleteMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<Void> delete(@Parameter(description = "管道 ID") @PathVariable Long id) {
        pipelineService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "启动管道", description = "有 savepoint 优先恢复（不丢不重）；无 savepoint 先预检源库再按启动位点提交 Flink 作业")
    @PostMapping("/{id}/start")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<CdcPipelineDTO> start(@Parameter(description = "管道 ID") @PathVariable Long id) {
        return Result.ok(pipelineService.start(id));
    }

    @Operation(summary = "停止管道", description = "cancel-with-savepoint：保存 savepoint 后停止，供下次启动恢复")
    @PostMapping("/{id}/stop")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<CdcPipelineDTO> stop(@Parameter(description = "管道 ID") @PathVariable Long id) {
        return Result.ok(pipelineService.stop(id));
    }

    @Operation(summary = "管道运行日志", description = "创建/启停/状态变更/延迟告警，id 倒序分页")
    @GetMapping("/{id}/logs")
    public Result<PageResult<CdcPipelineLogDTO>> logs(@Parameter(description = "管道 ID") @PathVariable Long id,
                                                      @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
                                                      @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(pipelineService.logs(id, page, pageSize));
    }

    @Operation(summary = "刷新 Doris catalog", description = "REFRESH CATALOG，让 Doris 外部表感知湖仓新表/新数据")
    @GetMapping("/{id}/refresh-catalog")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    public Result<Void> refreshCatalog(@Parameter(description = "管道 ID") @PathVariable Long id) {
        pipelineService.refreshCatalog(id);
        return Result.ok(null);
    }
}
