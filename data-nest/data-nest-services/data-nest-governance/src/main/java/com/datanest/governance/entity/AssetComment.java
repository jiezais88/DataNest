package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产评论（Sprint 8 F1 DC-08）：按表维度（不嵌套），软删保留历史。
 */
@Data
@TableName("asset_comment")
public class AssetComment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** metadata_table.id */
    private Long tableId;

    /** 评论人（sys_user.id；用户删除后前端展示「已注销」） */
    private Long userId;

    /** 评论内容（≤2000 字） */
    private String content;

    /** 软删标记：0-正常 1-已删除 */
    private Integer deleted;

    /** 删除人ID（作者自删/治理员/超管删均记录） */
    private Long deletedBy;

    /** 删除时间 */
    private LocalDateTime deletedAt;

    private Long createdBy;

    private LocalDateTime createdAt;
}
