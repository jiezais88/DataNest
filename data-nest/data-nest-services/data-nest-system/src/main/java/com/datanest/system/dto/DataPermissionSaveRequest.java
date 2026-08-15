package com.datanest.system.dto;

import com.datanest.common.model.DataPermissionGrant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色数据权限保存请求（Sprint 11 F2，权限配置页「保存权限配置」）。
 * <p>
 * 全量重建语义：后端先删该角色全部数据权限记录，再按 {@code grants} 重建。
 * {@code dataScope} 显式声明默认范围：FULL=全部数据可见（白名单忽略）；WHITELIST=仅授权数据可见。
 */
@Schema(description = "角色数据权限保存请求")
public record DataPermissionSaveRequest(
        @Schema(description = "角色 ID") @NotNull Long roleId,
        @Schema(description = "数据权限默认范围（FULL=全部数据 / WHITELIST=仅授权数据）") @NotBlank String dataScope,
        @Schema(description = "数据权限白名单（三级授权项列表；dataScope=WHITELIST 时生效）")
        @NotNull List<DataPermissionGrant> grants
) {
}
