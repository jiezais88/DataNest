package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collect_change_detail")
public class CollectChangeDetail {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long historyId;

    private String changeType;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String columnName;

    private String oldValue;

    private String newValue;

    private LocalDateTime createdAt;
}
