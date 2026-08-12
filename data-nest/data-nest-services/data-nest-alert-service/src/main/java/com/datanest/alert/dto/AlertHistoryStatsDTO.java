package com.datanest.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "告警历史统计（列表页顶部统计卡，按时间范围 + 对象维度聚合）")
@Data
public class AlertHistoryStatsDTO {

    @Schema(description = "失败告警数（alertType=FAILURE）")
    private Long failure;

    @Schema(description = "超时告警数（alertType=TIMEOUT）")
    private Long timeout;

    @Schema(description = "延迟超限告警数（alertType=LAG_EXCEEDED）")
    private Long lagExceeded;

    @Schema(description = "外部停止告警数（alertType=EXTERNAL_STOP）")
    private Long externalStop;

    @Schema(description = "恢复成功通知数（alertType=SUCCESS）")
    private Long success;

    @Schema(description = "发送失败数（sendStatus=FAILED）")
    private Long sendFailed;
}
