package com.datanest.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用审计日志实体（Sprint 11 F1）。
 * <p>
 * 只增不改不删：无 update/delete 接口；operator_name 落库时由 system 统一回填。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long operatorId;
    private String operatorName;
    private String opType;
    private String resourceType;
    private String resourceId;
    private String resourceName;
    private String content;
    private String result;
    private String errorMessage;
    private String clientIp;
    private LocalDateTime createdAt;
}
