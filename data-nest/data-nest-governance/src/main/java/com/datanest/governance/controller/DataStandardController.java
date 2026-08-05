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
import com.datanest.task.core.service.ComplianceCheckService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 数据标准 Controller。
 * - 标准配置（命名规范/字段类型标准增删改查）：仅治理员/超管。
 * - 合规检查（执行/结果/分页/忽略/导出）：另开放工程师（DATA_ENGINEER）查看与操作（PRD §7）。
 */
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

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/naming-standards")
    public Result<NamingStandardDTO> createNamingStandard(@Valid @RequestBody NamingStandardCreateRequest request) {
        return Result.ok(namingStandardService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/naming-standards/{id}")
    public Result<NamingStandardDTO> updateNamingStandard(@PathVariable Long id,
                                                          @Valid @RequestBody NamingStandardUpdateRequest request) {
        return Result.ok(namingStandardService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @DeleteMapping("/naming-standards/{id}")
    public Result<Void> deleteNamingStandard(@PathVariable Long id) {
        namingStandardService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/naming-standards/{id}")
    public Result<NamingStandardDTO> getNamingStandard(@PathVariable Long id) {
        return Result.ok(namingStandardService.getById(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/naming-standards/page")
    public Result<PageResult<NamingStandardDTO>> listNamingStandards(@RequestBody NamingStandardQueryRequest request) {
        return Result.ok(namingStandardService.list(request));
    }

    // ---------------- 字段类型标准（治理员/超管） ----------------

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/field-type-standards")
    public Result<FieldTypeStandardDTO> createFieldTypeStandard(@Valid @RequestBody FieldTypeStandardCreateRequest request) {
        return Result.ok(fieldTypeStandardService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/field-type-standards/{id}")
    public Result<FieldTypeStandardDTO> updateFieldTypeStandard(@PathVariable Long id,
                                                                @Valid @RequestBody FieldTypeStandardUpdateRequest request) {
        return Result.ok(fieldTypeStandardService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @DeleteMapping("/field-type-standards/{id}")
    public Result<Void> deleteFieldTypeStandard(@PathVariable Long id) {
        fieldTypeStandardService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/field-type-standards/{id}")
    public Result<FieldTypeStandardDTO> getFieldTypeStandard(@PathVariable Long id) {
        return Result.ok(fieldTypeStandardService.getById(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/field-type-standards/page")
    public Result<PageResult<FieldTypeStandardDTO>> listFieldTypeStandards(@RequestBody FieldTypeStandardQueryRequest request) {
        return Result.ok(fieldTypeStandardService.list(request));
    }

    // ---------------- 合规检查（治理员/超管/工程师） ----------------

    // 运行扫描仅治理员/超管（PRD §8：运行标准合规扫描工程师 ❌）；结果查看/忽略/导出放开工程师
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/compliance-check")
    public Result<List<ComplianceCheckResultDTO>> runComplianceCheck(@RequestBody ComplianceCheckRequest request) {
        return Result.ok(complianceCheckService.check(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/results")
    public Result<List<ComplianceCheckResultDTO>> listComplianceCheckResults(@RequestBody ComplianceCheckRequest request) {
        return Result.ok(complianceCheckService.listResults(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/page")
    public Result<PageResult<ComplianceCheckResultDTO>> pageComplianceCheckResults(@RequestBody ComplianceCheckPageRequest request) {
        return Result.ok(complianceCheckService.page(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/ignore/{resultId}")
    public Result<Void> ignoreComplianceCheckResult(@PathVariable Long resultId) {
        complianceCheckService.ignore(resultId, currentUserId());
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/unignore/{resultId}")
    public Result<Void> unignoreComplianceCheckResult(@PathVariable Long resultId) {
        complianceCheckService.unignore(resultId);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/compliance-check/export")
    public ResponseEntity<byte[]> exportComplianceCheck(@RequestBody ComplianceCheckRequest request) {
        String csv = complianceCheckService.export(request);
        String filename = "compliance_check_" + System.currentTimeMillis() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
