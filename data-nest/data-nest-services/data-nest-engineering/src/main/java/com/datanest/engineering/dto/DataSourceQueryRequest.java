package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "数据源分页查询请求")
public class DataSourceQueryRequest {

    @Schema(description = "关键字（模糊匹配数据源名称）")
    private String keyword;
    @Schema(description = "数据源类型过滤（MYSQL/POSTGRESQL/DORIS/ORACLE/SQLSERVER）")
    private String type;
    @Schema(description = "连接状态过滤（NORMAL/ERROR）")
    private String status;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
