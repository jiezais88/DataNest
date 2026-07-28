package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("metadata_table")
public class MetadataTable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long datasourceId;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String tableComment;

    private String manualComment;

    private String sourceStatus;

    private String sourceType;

    private Integer columnCount;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
