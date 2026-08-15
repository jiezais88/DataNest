package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.system.dto.DataPermissionSaveRequest;
import com.datanest.system.dto.DataPermissionVO;
import com.datanest.system.service.DataPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据权限配置（Sprint 11 F2，权限配置页）。
 * <p>
 * 角色 → 数据源/库/表三级白名单；保存/查询均属系统管理类「权限配置」能力（data_permission:manage）。
 */
@Tag(name = "数据权限配置", description = "角色三级数据权限白名单配置")
@RestController
@RequestMapping("/data-permissions")
public class DataPermissionController {

    private final DataPermissionService dataPermissionService;

    public DataPermissionController(DataPermissionService dataPermissionService) {
        this.dataPermissionService = dataPermissionService;
    }

    @Operation(summary = "保存角色数据权限", description = "全量重建白名单；空 grants 恢复默认全量可见")
    @SaCheckPermission(PermissionCode.DATA_PERMISSION_MANAGE)
    @PostMapping
    public Result<Void> save(@Valid @RequestBody DataPermissionSaveRequest req) {
        dataPermissionService.save(req);
        return Result.ok(null);
    }

    @Operation(summary = "查询角色数据权限", description = "权限配置页回显该角色已配置的白名单")
    @SaCheckPermission(PermissionCode.DATA_PERMISSION_MANAGE)
    @GetMapping("/{roleId}")
    public Result<List<DataPermissionVO>> listByRole(@Parameter(description = "角色 ID") @PathVariable Long roleId) {
        return Result.ok(dataPermissionService.listByRole(roleId));
    }
}
