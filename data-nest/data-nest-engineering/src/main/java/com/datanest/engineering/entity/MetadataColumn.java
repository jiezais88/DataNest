package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("metadata_column")
public class MetadataColumn {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tableId;

    private String columnName;

    private String dataType;

    private String columnComment;

    private String manualComment;

    private Boolean nullable;

    private String columnDefault;

    private Integer ordinalPosition;

    private String sourceType;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
