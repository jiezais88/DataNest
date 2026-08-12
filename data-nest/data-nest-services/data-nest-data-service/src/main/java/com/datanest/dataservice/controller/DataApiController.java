package com.datanest.dataservice.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.dataservice.dto.DataApiCreateRequest;
import com.datanest.dataservice.dto.DataApiDetailDTO;
import com.datanest.dataservice.dto.DataApiPageItem;
import com.datanest.dataservice.dto.DataApiSummaryDTO;
import com.datanest.dataservice.dto.DataApiUpdateRequest;
import com.datanest.dataservice.service.DataApiService;
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
 * 数据 API 管理（Sprint 10 F2）。
 * <p>
 * 查看（列表/详情）四角色 OR；创建/编辑/发布/下线/删除仅超管/工程师（对齐 PRD §8 权限矩阵）。
 * 类级四角色 + 方法级写角色同时生效（与语义 = 写操作需超管/工程师）。
 */
@Tag(name = "数据 API 管理", description = "表级参数化 API 定义 / 生命周期 / 自动文档（Sprint 10 F2）")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
@RestController
@RequestMapping("/apis")
public class DataApiController {

    private final DataApiService dataApiService;

    public DataApiController(DataApiService dataApiService) {
        this.dataApiService = dataApiService;
    }

    @Operation(summary = "创建 API", description = "校验表敏感度（机密禁止 / 内部需超管开白，fail-closed）；路径归一为 /open-api/v1/{段} 且唯一；创建后为 CREATED 未发布")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DataApiDetailDTO> create(@Valid @RequestBody DataApiCreateRequest request) {
        return Result.ok(dataApiService.create(request));
    }

    @Operation(summary = "API 列表（分页）", description = "scope=mine 仅看我创建的（默认全量）；keyword 匹配名称/路径；status 精确过滤；含绑定 Key 数与近 7 天调用")
    @GetMapping("/page")
    public Result<PageResult<DataApiPageItem>> page(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return Result.ok(dataApiService.page(page, pageSize, scope, keyword, status));
    }

    @Operation(summary = "API 汇总（列表页统计卡）", description = "已发布/待发布/已下线计数 + 近 7 天总调用")
    @GetMapping("/summary")
    public Result<DataApiSummaryDTO> summary() {
        return Result.ok(dataApiService.summary());
    }

    @Operation(summary = "API 详情", description = "定义（filters/fields）+ 自动文档（参数说明 + curl 示例）+ 绑定 Key + 近 7 天调用")
    @GetMapping("/{id}")
    public Result<DataApiDetailDTO> detail(@PathVariable("id") Long id) {
        return Result.ok(dataApiService.detail(id));
    }

    @Operation(summary = "编辑 API", description = "名称/路径/参数/字段/排序/分页可改；数据源/库/表绑定不可改；编辑前重新过敏感度闸门")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DataApiDetailDTO> update(@PathVariable("id") Long id,
                                           @Valid @RequestBody DataApiUpdateRequest request) {
        return Result.ok(dataApiService.update(id, request));
    }

    @Operation(summary = "发布", description = "CREATED/DISABLED → PUBLISHED（已发布幂等）；发布前重新过敏感度闸门")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable("id") Long id) {
        dataApiService.publish(id);
        return Result.ok(null);
    }

    @Operation(summary = "下线", description = "PUBLISHED → DISABLED（已下线幂等）；下线后对外调用返回 404")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable("id") Long id) {
        dataApiService.disable(id);
        return Result.ok(null);
    }

    @Operation(summary = "删除（软删）", description = "软删保留调用统计（api_call_log），释放路径占用，并清理 Key 绑定关系")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        dataApiService.delete(id);
        return Result.ok(null);
    }
}
