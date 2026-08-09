package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "数据源连接测试结果")
public class TestConnectionResult {

    @Schema(description = "是否连接成功")
    private boolean success;
    @Schema(description = "结果消息")
    private String message;

    public TestConnectionResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
