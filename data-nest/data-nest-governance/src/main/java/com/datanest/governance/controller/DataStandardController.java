package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.*;
import com.datanest.governance.service.ComplianceCheckService;
import com.datanest.governance.service.FieldTypeStandardService;
import com.datanest.governance.service.NamingStandardService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
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

    // ---------------- 命名规范 ----------------

    @PostMapping("/naming-standards")
    public Result<NamingStandardDTO> createNamingStandard(@Valid @RequestBody NamingStandardCreateRequest request) {
        return Result.ok(namingStandardService.create(request));
    }

    @PutMapping("/naming-standards/{id}")
    public Result<NamingStandardDTO> updateNamingStandard(@PathVariable Long id,
                                                          @Valid @RequestBody NamingStandardUpdateRequest request) {
        return Result.ok(namingStandardService.update(id, request));
    }

    @DeleteMapping("/naming-standards/{id}")
    public Result<Void> deleteNamingStandard(@PathVariable Long id) {
        namingStandardService.delete(id);
        return Result.ok(null);
    }

    @GetMapping("/naming-standards/{id}")
    public Result<NamingStandardDTO> getNamingStandard(@PathVariable Long id) {
        return Result.ok(namingStandardService.getById(id));
    }

    @PostMapping("/naming-standards/page")
    public Result<PageResult<NamingStandardDTO>> listNamingStandards(@RequestBody NamingStandardQueryRequest request) {
        return Result.ok(namingStandardService.list(request));
    }

    // ---------------- 字段类型标准 ----------------

    @PostMapping("/field-type-standards")
    public Result<FieldTypeStandardDTO> createFieldTypeStandard(@Valid @RequestBody FieldTypeStandardCreateRequest request) {
        return Result.ok(fieldTypeStandardService.create(request));
    }

    @PutMapping("/field-type-standards/{id}")
    public Result<FieldTypeStandardDTO> updateFieldTypeStandard(@PathVariable Long id,
                                                                @Valid @RequestBody FieldTypeStandardUpdateRequest request) {
        return Result.ok(fieldTypeStandardService.update(id, request));
    }

    @DeleteMapping("/field-type-standards/{id}")
    public Result<Void> deleteFieldTypeStandard(@PathVariable Long id) {
        fieldTypeStandardService.delete(id);
        return Result.ok(null);
    }

    @GetMapping("/field-type-standards/{id}")
    public Result<FieldTypeStandardDTO> getFieldTypeStandard(@PathVariable Long id) {
        return Result.ok(fieldTypeStandardService.getById(id));
    }

    @PostMapping("/field-type-standards/page")
    public Result<PageResult<FieldTypeStandardDTO>> listFieldTypeStandards(@RequestBody FieldTypeStandardQueryRequest request) {
        return Result.ok(fieldTypeStandardService.list(request));
    }

    // ---------------- 合规检查 ----------------

    @PostMapping("/compliance-check")
    public Result<List<ComplianceCheckResultDTO>> runComplianceCheck(@RequestBody ComplianceCheckRequest request) {
        return Result.ok(complianceCheckService.check(request));
    }

    @PostMapping("/compliance-check/results")
    public Result<List<ComplianceCheckResultDTO>> listComplianceCheckResults(@RequestBody ComplianceCheckRequest request) {
        return Result.ok(complianceCheckService.listResults(request));
    }
}
