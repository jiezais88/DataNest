package com.datanest.governance.util;

import java.util.Map;

/**
 * 导出中文标签字典：面向用户的 CSV 导出不出现英文枚举值。
 * 与前端标签保持一致（frontend `types/quality.ts` QUALITY_TYPE_LABEL、
 * `quality-report/constants.ts` LEVEL_LABEL、`compliance/index.tsx` OBJECT_LABEL/VIOLATION_LABEL）。
 * 未知值原样返回（新增枚举兜底可见，不丢信息）。
 */
public final class ExportLabels {

    private ExportLabels() {
    }

    /** 质量规则类型 */
    private static final Map<String, String> QUALITY_RULE_TYPE = Map.of(
            "COMPLETENESS", "完整性",
            "UNIQUENESS", "唯一性",
            "RANGE", "值域范围",
            "CUSTOM_SQL", "自定义 SQL",
            "PYTHON", "Python");

    /** 质量检查判定级别 */
    private static final Map<String, String> RESULT_LEVEL = Map.of(
            "SEVERE", "严重",
            "WARNING", "警告",
            "PASS", "通过",
            "UNAVAILABLE", "不可用");

    /** 合规检查对象类型 */
    private static final Map<String, String> COMPLIANCE_OBJECT_TYPE = Map.of(
            "TABLE", "表名",
            "COLUMN", "字段名");

    /** 合规违规类型 */
    private static final Map<String, String> COMPLIANCE_VIOLATION_TYPE = Map.of(
            "NAMING", "命名规范",
            "TYPE", "字段类型");

    public static String qualityRuleType(String value) {
        return label(QUALITY_RULE_TYPE, value);
    }

    public static String resultLevel(String value) {
        return label(RESULT_LEVEL, value);
    }

    public static String complianceObjectType(String value) {
        return label(COMPLIANCE_OBJECT_TYPE, value);
    }

    public static String complianceViolationType(String value) {
        return label(COMPLIANCE_VIOLATION_TYPE, value);
    }

    private static String label(Map<String, String> map, String value) {
        return value == null ? null : map.getOrDefault(value, value);
    }
}
