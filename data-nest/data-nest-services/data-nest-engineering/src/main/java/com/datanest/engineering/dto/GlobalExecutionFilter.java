package com.datanest.engineering.dto;

/**
 * 全局执行历史查询条件
 * - 与 PRD §6.7.3 筛选条件对齐：所属 DAG（模糊）/ 状态 / 触发方式 / 时间范围 / 分页
 * - startTimeFrom / startTimeTo 接受 ISO 字符串（兼容带 'Z' 后缀的 UTC 写法，如
 *   "2026-07-28T00:00:00Z"，以及无时区的 "2026-07-28T00:00:00"）；
 *   controller 负责把 String 解析为 LocalDateTime（容器时区 Asia/Shanghai，shared-common.yaml）
 *   避免 Spring 内置 @DateTimeFormat 对 'Z' 处理不一致的坑。
 */
public class GlobalExecutionFilter {

    /** DAG 名称模糊匹配；null/空 视为全部 */
    private String dagName;

    /** 所属项目名称模糊匹配；null/空 视为全部 */
    private String projectName;

    /** DAG id 精确过滤（任务列表「历史」跳入时只展示该 DAG）；null 视为全部 */
    private Long dagId;

    /** RUNNING / SUCCESS / FAILED / TERMINATED；null/空 视为全部 */
    private String status;

    /** MANUAL / CRON；null/空 视为全部 */
    private String triggerType;

    /** 执行时间下界（inclusive）；null/空 视为无下界 */
    private String startTimeFrom;

    /** 执行时间上界（exclusive）；null/空 视为无上界 */
    private String startTimeTo;

    private long page = 1;

    private long pageSize = 20;

    public String getDagName() {
        return dagName;
    }

    public void setDagName(String dagName) {
        this.dagName = dagName;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public Long getDagId() {
        return dagId;
    }

    public void setDagId(Long dagId) {
        this.dagId = dagId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getStartTimeFrom() {
        return startTimeFrom;
    }

    public void setStartTimeFrom(String startTimeFrom) {
        this.startTimeFrom = startTimeFrom;
    }

    public String getStartTimeTo() {
        return startTimeTo;
    }

    public void setStartTimeTo(String startTimeTo) {
        this.startTimeTo = startTimeTo;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }
}
