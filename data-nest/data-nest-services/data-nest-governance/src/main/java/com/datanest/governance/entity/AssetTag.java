package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产标签字典（Sprint 8 F1 DC-06）：平台级标签，同名复用。
 */
@Data
@TableName("asset_tag")
public class AssetTag {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 标签名（全局唯一） */
    private String name;

    private Long createdBy;

    private LocalDateTime createdAt;
}
