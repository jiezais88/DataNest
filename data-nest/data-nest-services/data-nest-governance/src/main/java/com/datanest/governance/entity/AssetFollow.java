package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产关注（Sprint 8 F1 DC-07）：个人维度（user_id + table_id 唯一），
 * 变更动态复用 collect_change_detail，不新建通知表。
 */
@Data
@TableName("asset_follow")
public class AssetFollow {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关注人（sys_user.id） */
    private Long userId;

    /** metadata_table.id */
    private Long tableId;

    /** 关注时间 */
    private LocalDateTime createdAt;
}
