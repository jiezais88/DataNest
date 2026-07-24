package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务间直接读表使用的轻量实体，仅映射 collect_task 的部分字段。
 */
@Data
@TableName("collect_task")
public class CollectTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private Long datasourceId;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
