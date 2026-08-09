package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "同步任务分页查询请求")
public class SyncJobQueryRequest {

    @Schema(description = "关键字（模糊匹配任务名称）")
    private String keyword;
    @Schema(description = "调度状态过滤（NORMAL/PAUSED）")
    private String status;
    @Schema(description = "触发方式过滤（MANUAL/CRON）")
    private String triggerType;
    @Schema(description = "页码，从 1 开始")
    private long page = 1;
    @Schema(description = "每页条数")
    private long pageSize = 10;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
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
