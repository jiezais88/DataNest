package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 内部创建采集任务请求（Sprint 7 DD-09 任务模板一键创建）。
 * <p>
 * 对齐 governance 的 CollectTaskCreateRequest 字段，额外携带 createdBy
 * （内部 Feign 调用无登录上下文，由调用方显式传入当前用户 ID）。
 * 字段非空校验由 governance 服务端负责（api 模块不引入 jakarta.validation）。
 */
@Data
public class CollectTaskCreateInternalRequest {

    private String name;

    private Long datasourceId;

    private List<String> scope;

    private String collectMode;

    private String triggerType;

    private String cronExpression;

    private String description;

    /** 创建人 ID（由调用方服务从登录上下文取得） */
    private Long createdBy;
}
