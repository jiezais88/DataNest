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

    private String password;

    private String status;

    private String description;

    private LocalDateTime lastConnectTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
