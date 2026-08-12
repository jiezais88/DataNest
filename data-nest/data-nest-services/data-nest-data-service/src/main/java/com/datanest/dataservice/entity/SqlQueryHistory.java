package com.datanest.dataservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 查询历史（Sprint 10 F1 SQL 终端）。
 */
@Data
@TableName("sql_query_history")
@Schema(description = "SQL 查询历史")
public class SqlQueryHistory {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "数据源 ID（内置 Doris 为 -1）")
    private Long datasourceId;

    @Schema(description = "SQL 文本")
    private String sqlText;

    @Schema(description = "执行耗时毫秒")
    private Integer durationMs;

    @Schema(description = "返回行数")
    private Integer rowCount;

    @Schema(description = "错误信息（成功查询为 null；失败查询记录错误详情供历史回显）")
    private String errorMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
