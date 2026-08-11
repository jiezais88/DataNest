package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.*;
import com.datanest.governance.service.FieldTypeStandardService;
import com.datanest.governance.service.NamingStandardService;
import com.datanest.task.core.dto.ComplianceCheckPageRequest;
import com.datanest.task.core.dto.ComplianceCheckRequest;
import com.datanest.task.core.dto.ComplianceCheckResultDTO;
import com.datanest.task.core.dto.ComplianceCheckSummaryDTO;
import com.datanest.governance.service.ComplianceCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 数据标准 Controller。
 * - 标准配置（命名规范/字段类型标准增删改查）：仅治理员/超管。
 * - 合规检查（执行/结果/分页/忽略/导出）：另开放工程师（DATA_ENGINEER）查看与操作（PRD §7）。
 */
@Tag(name = "数据标准", description = "命名规范 / 字段类型标准配置与标准合规检查")
@RestController
@RequestMapping("/data-standards")
public class DataStandardController {

    private final NamingStandardService namingStandardService;
    private final FieldTypeStandardService fieldTypeStandardService;
    private final ComplianceCheckService complianceCheckService;

    public DataStandardController(NamingStandardService namingStandardService,
                                  FieldTypeStandardService fieldTypeStandardService,
                                  ComplianceCheckService complianceCheckService) {
        this.namingStandardService = namingStandardService;
        this.fieldTypeStandardService = fieldTypeStandardService;
        this.complianceCheckService = complianceCheckService;
    }

    // ---------------- 命名规范（治理员/超管） ----------------

    @Operation(summary = "创建命名规范")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/naming-standards")
    public Result<NamingStandardDTO> createNamingStandard(@Valid @RequestBody NamingStandardCreateRequest request) {
        return Result.ok(namingStandardService.create(request));
    }

    @Operation(summary = "编辑命名规范")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/naming-standards/{id}")
    public Result<NamingStandardDTO> updateNamingStandard(@Parameter(description = "命名规范 ID") @PathVariable Long id,
                                                          @Valid @RequestBody NamingStandardUpdateRequest request) {
        return Result.ok(namingStandardService.update(id, request));
    }

    @Operation(summary = "删除命名规范")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @DeleteMapping("/naming-standards/{id}")
    public Result<Void> deleteNamingStandard(@Parameter(description = "命名规范 ID") @PathVariable Long id) {
        namingStandardService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "命名规范详情")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/naming-standards/{id}")
    public Result<NamingStandardDTO> getNamingStandard(@Parameter(description = "命名规范 ID") @PathVariable Long id) {
        return Result.ok(namingStandardService.getById(id));
    }

    @Operation(summary = "命名规范分页列表")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/naming-standards/page")
    public Result<PageResult<NamingStandardDTO>> listNamingStandards(@RequestBody NamingStandardQueryRequest request) {
        return Result.ok(namingStandardService.list(request));
    }

    // ---------------- 字段类型标准（治理员/超管） ----------------

    @Operation(summary = "创建字段类型标准")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/field-type-standards")
    public Result<FieldTypeStandardDTO> createFieldTypeStandard(@Valid @RequestBody FieldTypeStandardCreateRequest request) {
        return Result.ok(fieldTypeStandardService.create(request));
    }

    @Operation(summary = "编辑字段类型标准")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/field-type-standards/{id}")
    public Result<FieldTypeStandardDTO> updateFieldTypeStandard(@Parameter(description = "字段类型标准 ID") @PathVariable Long id,
                                                                @Valid @RequestBody FieldTypeStandardUpdateRequest request) {
        return Result.ok(fieldTypeStandardService.update(id, request));
    }

    @Operation(summary = "删除字段类型标准")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @DeleteMapping("/field-type-standards/{id}")
    public Result<Void> deleteFieldTypeStandard(@Parameter(description = "字段类型标准 ID") @PathVariable Long id) {
        fieldTypeStandardService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "字段类型标准详情")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/field-type-standards/{id}")
    public Result<FieldTypeStandardDTO> getFieldTypeStandard(@Parameter(description = "字段类型标准 ID") @PathVariable Long id) {
        return Result.ok(fieldTypeStandardService.getById(id));
    }

    @Operation(summary = "字段类型标准分页列表")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/field-type-standards/page")
    public Result<PageResult<FieldTypeStandardDTO>> listFieldTypeStandards(@RequestBody FieldTypeStandardQueryRequest request) {
        return Result.ok(fieldTypeStandardService.list(request));
    }

    // ---------------- 合规检查（治理员/超管/工程师） ----------------

    // 运行扫描仅治理员/超管（PRD §8：运行标准合规扫描工程师 ❌）；结果查看/忽略/导出放开工程师
    @Operation(summary = "运行标准合规扫描")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/compliance-check")
    public Result<List<ComplianceCheckResultDTO>> runComplianceCheck(@RequestBody ComplianceCheckRequest request) {
        return Result.ok(complianceCheckService.check(request));
    }

    @Operation(summary = "合规检查结果列表")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/results")
    public Result<List<ComplianceCheckResultDTO>> listComplianceCheckResults(@RequestBody ComplianceCheckRequest request) {
        return Result.ok(complianceCheckService.listResults(request));
    }

    @Operation(summary = "合规检查结果分页")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/page")
    public Result<PageResult<ComplianceCheckResultDTO>> pageComplianceCheckResults(@RequestBody ComplianceCheckPageRequest request) {
        return Result.ok(complianceCheckService.page(request));
    }

    @Operation(summary = "合规检查结果汇总")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/summary")
    public Result<ComplianceCheckSummaryDTO> summaryComplianceCheck(@RequestBody ComplianceCheckRequest request) {
        return Result.ok(complianceCheckService.summary(request));
    }

    @Operation(summary = "忽略合规检查结果")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/ignore/{resultId}")
    public Result<Void> ignoreComplianceCheckResult(@Parameter(description = "检查结果 ID") @PathVariable Long resultId) {
        complianceCheckService.ignore(resultId, currentUserId());
        return Result.ok(null);
    }

    @Operation(summary = "取消忽略合规检查结果")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/unignore/{resultId}")
    public Result<Void> unignoreComplianceCheckResult(@Parameter(description = "检查结果 ID") @PathVariable Long resultId) {
        complianceCheckService.unignore(resultId);
        return Result.ok(null);
    }

    @Operation(summary = "导出合规检查结果 CSV")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/export")
    public void exportComplianceCheck(@RequestBody ComplianceCheckRequest request,
                                      HttpServletResponse response) throws IOException {
        // 产品化文件名：DataNest-标准合规检查-日期.csv；ASCII 兜底 + RFC5987 中文编码（导出统一规范：void + 响应流）
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = "DataNest-标准合规检查-" + date + ".csv";
        String asciiFilename = "DataNest-compliance-check-" + date + ".csv";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + asciiFilename + "\"; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8));
        response.setContentType("text/csv;charset=UTF-8");
        complianceCheckService.export(request, response.getOutputStream());
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
