package com.datanest.dataservice.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.dataservice.dto.ApiKeyCreateRequest;
import com.datanest.dataservice.dto.ApiKeyCreateResult;
import com.datanest.dataservice.dto.ApiKeyDetailDTO;
import com.datanest.dataservice.dto.ApiKeyPageItem;
import com.datanest.dataservice.dto.ApiKeyUpdateRequest;
import com.datanest.dataservice.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API Key 管理（Sprint 10 F2）。
 * <p>
 * 查看（列表）四角色 OR；创建/编辑/启停/删除仅超管/工程师（对齐 PRD §8 权限矩阵）。
 */
@Tag(name = "API Key 管理", description = "Key 创建（明文仅一次）/ 启停 / 绑定 API / 僵尸 Key 识别（Sprint 10 F2）")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
@RestController
@RequestMapping("/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Operation(summary = "创建 Key", description = "生成一次性明文（K- 前缀，仅本次返回，后端只存 SHA-256 哈希）；可同时绑定 API")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<ApiKeyCreateResult> create(@Valid @RequestBody ApiKeyCreateRequest request) {
        return Result.ok(apiKeyService.create(request));
    }

    @Operation(summary = "Key 列表（分页）", description = "keyword 匹配名称；status 精确过滤；含绑定 API 数与近 7 天调用（0 = 僵尸 Key）")
    @GetMapping("/page")
    public Result<PageResult<ApiKeyPageItem>> page(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return Result.ok(apiKeyService.page(page, pageSize, keyword, status));
    }

    @Operation(summary = "Key 详情", description = "编辑弹窗预填当前绑定 API；明文 Key 只在创建时返回，详情不含")
    @GetMapping("/{id}")
    public Result<ApiKeyDetailDTO> detail(@PathVariable("id") Long id) {
        return Result.ok(apiKeyService.detail(id));
    }

    @Operation(summary = "编辑 Key", description = "改名 / 限流 QPS / 全量重绑 API")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody ApiKeyUpdateRequest request) {
        apiKeyService.update(id, request);
        return Result.ok(null);
    }

    @Operation(summary = "快捷启用", description = "操作列一步恢复（已启用幂等）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable("id") Long id) {
        apiKeyService.enable(id);
        return Result.ok(null);
    }

    @Operation(summary = "快捷禁用", description = "泄露 1 步处置（已禁用幂等）；禁用后对外调用立即 401")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable("id") Long id) {
        apiKeyService.disable(id);
        return Result.ok(null);
    }

    @Operation(summary = "删除 Key", description = "同时清理 API 绑定与管道订阅授权")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        apiKeyService.delete(id);
        return Result.ok(null);
    }
}
