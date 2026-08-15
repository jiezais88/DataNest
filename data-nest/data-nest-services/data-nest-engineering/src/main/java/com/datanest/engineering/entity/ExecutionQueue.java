package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行队列（Sprint 11 F3 任务资源队列）
 * 对应表 execution_queue
 */
@Data
@TableName("execution_queue")
public class ExecutionQueue {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 队列名（唯一） */
    private String queueName;

    /** 最大并发数（该队列同时 RUNNING 的执行实例上限） */
    private Integer maxConcurrency;

    /** 队列描述 */
    private String description;

    /** 系统队列标记（default 内置不可删、名称不可改） */
    private Boolean isSystem;

    private Long createdBy;

    private LocalDateTime createdAt;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
