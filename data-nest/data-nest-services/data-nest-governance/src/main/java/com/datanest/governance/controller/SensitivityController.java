package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.SensitivityAuditItemDTO;
import com.datanest.governance.dto.SensitivityBatchUpdateRequest;
import com.datanest.governance.dto.SensitivityExemptRequest;
import com.datanest.governance.dto.SensitivityTableItemDTO;
import com.datanest.governance.dto.SensitivityUpdateRequest;
import com.datanest.governance.service.SensitivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据分级分类（Sprint 10 F5）：改级 / 批量改级 / API 特批开放 / 分级审计 / 分级列表。
 * <p>
 * 权限：改级/批量/审计/列表 = 治理员/超管；特批开放 = 仅超管（T6）。
 */
@Tag(name = "数据分级分类", description = "改级 / 批量改级 / API 特批开放 / 分级审计（Sprint 10 F5）")
@RestController
@RequestMapping("/metadata")
public class SensitivityController {

    private final SensitivityService sensitivityService;

    public SensitivityController(SensitivityService sensitivityService) {
        this.sensitivityService = sensitivityService;
    }

    @Operation(summary = "单表改级", description = "三级：PUBLIC/INTERNAL/CONFIDENTIAL，任意级别直接互转；降级确认由前端负责")
    @SaCheckPermission(PermissionCode.SENSITIVITY_CHANGE)
    @PutMapping("/tables/{tableId}/sensitivity")
    public Result<Integer> updateSensitivity(@Parameter(description = "元数据表 ID") @PathVariable Long tableId,
                                             @Valid @RequestBody SensitivityUpdateRequest request) {
        return Result.ok(sensitivityService.updateSensitivity(tableId, request.getNewLevel()));
    }

    @Operation(summary = "批量改级", description = "多表统一设为某级；全有或全无（任一表违反机密降级两步则整体拒绝）")
    @SaCheckPermission(PermissionCode.SENSITIVITY_BATCH_CHANGE)
    @PostMapping("/tables/sensitivity/batch")
    public Result<Integer> batchUpdateSensitivity(@Valid @RequestBody SensitivityBatchUpdateRequest request) {
        return Result.ok(sensitivityService.batchUpdateSensitivity(request.getTableIds(), request.getNewLevel()));
    }

    @Operation(summary = "内部表 API 特批开放", description = "仅超管；仅 INTERNAL 表可特批开放（机密表恒为 0 不可特批）")
    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/tables/{tableId}/api-exempt")
    public Result<Void> updateApiExempt(@Parameter(description = "元数据表 ID") @PathVariable Long tableId,
                                        @Valid @RequestBody SensitivityExemptRequest request) {
        sensitivityService.updateApiExempt(tableId, request.getApiExempted());
        return Result.ok(null);
    }

    @Operation(summary = "分级变更审计", description = "改级 + 特批开放操作留痕，回填操作人用户名")
    @SaCheckPermission(PermissionCode.SENSITIVITY_CHANGE)
    @GetMapping("/sensitivity/audit")
    public Result<PageResult<SensitivityAuditItemDTO>> pageAudit(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return Result.ok(sensitivityService.pageAudit(page, pageSize));
    }

    @Operation(summary = "分级表列表（分页）", description = "敏感度筛选 + 数据源筛选 + 库/模式/表关键词；仅 ONLINE 表")
    @SaCheckPermission(PermissionCode.SENSITIVITY_VIEW)
    @GetMapping("/sensitivity/tables")
    public Result<PageResult<SensitivityTableItemDTO>> pageTables(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize,
            @RequestParam(value = "sensitivityLevel", required = false) String sensitivityLevel,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "datasourceId", required = false) Long datasourceId) {
        return Result.ok(sensitivityService.pageTables(page, pageSize, sensitivityLevel, keyword, datasourceId));
    }
}
