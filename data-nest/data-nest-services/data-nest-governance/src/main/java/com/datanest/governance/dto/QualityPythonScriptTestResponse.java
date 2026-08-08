package com.datanest.governance.dto;

import lombok.Data;

import java.util.Map;

/**
 * Python 质量规则脚本试跑响应（Sprint 7 DG-10）。
 */
@Data
public class QualityPythonScriptTestResponse {

    /** 脚本是否执行成功（进程退出码 0） */
    private boolean success;

    /** check(df) 返回的结果 dict（success=true 时有值） */
    private Map<String, Object> result;

    /** 失败原因（stderr 截断；success=false 时有值） */
    private String error;

    /** 执行耗时（毫秒） */
    private Long durationMs;
}
