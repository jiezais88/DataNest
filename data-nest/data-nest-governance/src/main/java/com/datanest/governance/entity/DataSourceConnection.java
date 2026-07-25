package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("datasource_connection")
public class DataSourceConnection {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String type;

    private String host;

    private Integer port;

    private String databaseName;

    private String schemaName;

    private String username;

    private String encryptedPassword;

    private String description;

    private String status;

    private LocalDateTime lastTestTime;

    private String errorMessage;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
