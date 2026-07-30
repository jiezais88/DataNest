package com.datanest.engineering.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * DolphinScheduler 通用 API 响应包装
 * 形如：{"code":0,"msg":"success","data":...,"failed":false,"success":true}
 * 决策 ADR-S3-FJ：使用 fastjson2 替代 Jackson，DTO 默认忽略未知字段
 */
@Data
public class DsApiResponse<T> {

    private Integer code;

    private String msg;

    private T data;

    private Boolean failed;

    private Boolean success;

    @JSONField(name = "failed")
    public boolean isFailed() {
        return failed != null && failed;
    }

    /**
     * 业务成功：code==0 && !isFailed()
     */
    public boolean isOk() {
        return code != null && code == 0 && !isFailed();
    }

    /**
     * 抛出业务异常
     */
    public T requireData() {
        if (!isOk()) {
            throw new IllegalStateException("DS API error: code=" + code + ", msg=" + msg);
        }
        return data;
    }
}
