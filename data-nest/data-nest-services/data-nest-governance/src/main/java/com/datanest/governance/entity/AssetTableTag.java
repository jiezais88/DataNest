package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表-标签关联（Sprint 8 F1 DC-06）：metadata_table 与 asset_tag 多对多。
 */
@Data
@TableName("asset_table_tag")
public class AssetTableTag {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** metadata_table.id */
    private Long tableId;

    /** asset_tag.id */
    private Long tagId;

    private Long createdBy;

    private LocalDateTime createdAt;
}
