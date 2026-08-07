package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登记下次重试时间请求（失败收尾时在历史记录上写 next_retry_at）。
 */
@Data
public class RegisterRetryRequest {

    private LocalDateTime nextRetryAt;
}
