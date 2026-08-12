package com.datanest.dataservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Key-API 绑定（Sprint 10 F2）：一个 Key 可绑定多个 API，一个 API 可被多个 Key 调用。
 */
@Data
@TableName("api_key_binding")
@Schema(description = "Key-API 绑定")
public class ApiKeyBinding {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "API Key ID")
    private Long keyId;

    @Schema(description = "数据 API ID")
    private Long apiId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
