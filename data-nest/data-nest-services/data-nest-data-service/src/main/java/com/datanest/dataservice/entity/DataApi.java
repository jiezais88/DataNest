package com.datanest.dataservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据 API 定义（Sprint 10 F2）：表级参数化查询 API。
 * <p>
 * 软删（deleted=1）保留 api_call_log 调用统计；path 部分唯一索引仅约束未删除行。
 * params_json 为完整定义对象：{"filters":[{"field","type":"EQ|RANGE"}],"fields":[返回字段白名单]}。
 */
@Data
@TableName("data_api")
@Schema(description = "数据 API 定义")
public class DataApi {

    /** 状态：未发布 */
    public static final String STATUS_CREATED = "CREATED";
    /** 状态：已发布（可对外调用） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 状态：已下线 */
    public static final String STATUS_DISABLED = "DISABLED";

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "API 名称")
    private String name;

    @Schema(description = "对外路径（完整形态 /open-api/v1/{自定义段}，未删除行唯一）")
    private String path;

    @Schema(description = "请求方法（本期固定 GET）")
    private String method;

    @Schema(description = "数据源 ID（内置 Doris 为 -1）")
    private Long datasourceId;

    @Schema(description = "库名")
    private String databaseName;

    @Schema(description = "Schema 名（MySQL/Doris 为空）")
    private String schemaName;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "关联元数据表 ID（governance metadata_table）")
    private Long metadataTableId;

    @Schema(description = "API 定义 JSON：filters 参数化筛选 + fields 返回字段白名单")
    private String paramsJson;

    @Schema(description = "排序（如 cnt DESC，列名+方向白名单校验）")
    private String orderBy;

    @Schema(description = "是否分页：1 启用 / 0 关闭")
    private Integer paginated;

    @Schema(description = "pageSize 上限")
    private Integer pageSizeMax;

    @Schema(description = "状态：CREATED 未发布 / PUBLISHED 可调用 / DISABLED 下线")
    private String status;

    @Schema(description = "软删标记：0 正常 / 1 已删除")
    private Integer deleted;

    @Schema(description = "创建人 ID")
    private Long createdBy;

    @Schema(description = "更新人 ID")
    private Long updatedBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
