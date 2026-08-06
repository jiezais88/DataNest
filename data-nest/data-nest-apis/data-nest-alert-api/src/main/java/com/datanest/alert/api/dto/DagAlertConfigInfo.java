package com.datanest.alert.api.dto;

import lombok.Data;

/**
 * DAG 告警配置摘要（按 DAG 解析后的生效配置）。
 * <p>
 * 仅供内部调用方做本地阈值判断（如 job 的节点超时扫描），
 * 完整配置管理走 alert-service 的公开端点。
 */
@Data
public class DagAlertConfigInfo {

    /** 是否启用：1 启用，0 关闭 */
    private Integer enabled;

    /** 节点超时阈值（分钟） */
    private Integer timeoutMinutes;
}
