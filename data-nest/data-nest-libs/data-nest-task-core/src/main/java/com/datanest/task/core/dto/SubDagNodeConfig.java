package com.datanest.task.core.dto;

import lombok.Data;

import java.util.List;

/**
 * 子 DAG 节点配置（Sprint 5，task-core 共享）。
 * config JSON: {"type":"SUB_DAG","subDagId":123,"subDagName":"xxx","syncExecution":true,
 *               "paramMappings":[{"mainParam":"${biz_date}","subParam":"${sub_date}"}]}
 * Sprint 7 NG5：paramMappings 主→子参数下发（旧数据为 null 视为不传参，向后兼容）。
 */
@Data
public class SubDagNodeConfig {

    private String type = "SUB_DAG";

    private Long subDagId;

    /** 冗余名称，便于展示 */
    private String subDagName;

    /** true=同步执行（等待子 DAG 完成后继续）；false=异步执行（触发后继续） */
    private Boolean syncExecution = true;

    /** 参数映射列表：主 DAG 参数 → 子 DAG 参数（仅主→子单向下发） */
    private List<ParamMapping> paramMappings;

    /** 主→子参数映射项 */
    @Data
    public static class ParamMapping {
        /** 主 DAG 参数引用，如 "${biz_date}"（执行时解析为实际值） */
        private String mainParam;
        /** 子 DAG 参数名，如 "${sub_date}"（注入子 DAG 参数集） */
        private String subParam;
    }
}
