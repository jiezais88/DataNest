package com.datanest.dataservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key（Sprint 10 F2）：SHA-256 哈希存储，明文仅创建时展示一次（K- 前缀）。
 */
@Data
@TableName("api_key")
@Schema(description = "API Key")
public class ApiKey {

    /** 状态：启用 */
    public static final String STATUS_ENABLED = "ENABLED";
    /** 状态：禁用（调用立即 401） */
    public static final String STATUS_DISABLED = "DISABLED";

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "Key 名称")
    private String name;

    @Schema(description = "Key 的 SHA-256 哈希（hex）")
    private String keyHash;

    @Schema(description = "限流 QPS（该 Key 下所有 API 共享，F3 生效）")
    private Integer qpsLimit;

    @Schema(description = "状态：ENABLED 启用 / DISABLED 禁用")
    private String status;

    @Schema(description = "创建人 ID")
    private Long createdBy;

    @Schema(description = "更新人 ID")
    private Long updatedBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
