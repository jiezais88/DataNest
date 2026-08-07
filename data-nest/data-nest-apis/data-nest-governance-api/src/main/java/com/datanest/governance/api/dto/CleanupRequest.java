package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 低频运维清理请求（采集历史 / 质量检查历史 / 血缘记录共用）。
 */
@Data
public class CleanupRequest {

    /** 保留天数；null 时由服务端按各清理项的默认值处理 */
    private Integer retainDays;
}
