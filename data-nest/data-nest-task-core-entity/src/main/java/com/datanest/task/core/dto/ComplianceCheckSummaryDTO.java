package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 标准合规检查统计摘要（Sprint6 前端三格统计：不合规项 / 已忽略 / 合规率）。
 * <p>
 * 口径说明：
 * <ul>
 *   <li>nonCompliant：范围内未忽略的不合规项数（ignored=0）。</li>
 *   <li>ignored：范围内已忽略的不合规项数（ignored=1），已忽略视为已豁免/已整改，不拉低合规率。</li>
 *   <li>totalObjects：范围内在线元数据对象总数（表 + 字段），作为合规率分母。</li>
 *   <li>complianceRate：合规率 = (1 - nonCompliant / totalObjects) * 100，保留 1 位小数；totalObjects=0 时为 100。</li>
 * </ul>
 */
@Data
public class ComplianceCheckSummaryDTO {

    /** 未忽略的不合规项数。 */
    private Long nonCompliant;

    /** 已忽略的不合规项数。 */
    private Long ignored;

    /** 范围内在线元数据对象总数（表 + 字段）。 */
    private Long totalObjects;

    /** 合规率（0-100，保留 1 位小数）。 */
    private Double complianceRate;
}
