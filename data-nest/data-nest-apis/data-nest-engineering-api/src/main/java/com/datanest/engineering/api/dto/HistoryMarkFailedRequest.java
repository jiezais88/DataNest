package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 重试历史收尾请求（标 FAILED）。
 */
@Data
public class HistoryMarkFailedRequest {

    private String errorMessage;
}
