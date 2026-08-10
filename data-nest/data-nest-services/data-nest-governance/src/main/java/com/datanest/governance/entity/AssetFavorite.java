package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产收藏（Sprint 8 F1 DC-07）：个人维度（user_id + table_id 唯一）。
 */
@Data
@TableName("asset_favorite")
public class AssetFavorite {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 收藏人（sys_user.id） */
    private Long userId;

    /** metadata_table.id */
    private Long tableId;

    /** 收藏时间 */
    private LocalDateTime createdAt;
}
