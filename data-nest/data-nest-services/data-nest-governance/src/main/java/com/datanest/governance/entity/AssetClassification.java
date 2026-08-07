package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据资产分类（Sprint 7 F1）：数据域(DOMAIN)→主题(TOPIC)两级分类体系。
 */
@Data
@TableName("asset_classification")
public class AssetClassification {

    /** 层级：数据域（一级） */
    public static final String LEVEL_DOMAIN = "DOMAIN";
    /** 层级：主题（二级） */
    public static final String LEVEL_TOPIC = "TOPIC";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 层级：DOMAIN / TOPIC */
    private String level;

    /** 分类名称（同级唯一） */
    private String name;

    /** 父分类 ID（TOPIC 指向 DOMAIN；DOMAIN 为 NULL） */
    private Long parentId;

    /** 同级排序号 */
    private Integer sort;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 子分类列表（组树展示用，非表字段） */
    @TableField(exist = false)
    private List<AssetClassification> children;
}
