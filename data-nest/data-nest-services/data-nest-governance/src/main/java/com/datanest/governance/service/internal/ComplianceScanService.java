package com.datanest.governance.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.governance.entity.ComplianceCheckResult;
import com.datanest.governance.entity.FieldTypeStandard;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.entity.NamingStandard;
import com.datanest.governance.mapper.ComplianceCheckResultMapper;
import com.datanest.governance.mapper.FieldTypeStandardMapper;
import com.datanest.governance.mapper.MetadataColumnMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.governance.mapper.NamingStandardMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 标准合规全量扫描服务（治理域低频运维）。
 * <p>
 * 微服务化 4.1：由 task-core-governance 的 ComplianceCheckService.check 全量扫描路径下沉
 * （对齐原 StandardComplianceCheckHandler 的调用语义：不指定范围 = 全部在线数据源，
 * 命名规范 + 字段类型标准均检查）。扫描分支逻辑逐行对齐源实现，仅移除单表/分页/忽略等
 * 与全量定时扫描无关的旁支。
 */
@Service
public class ComplianceScanService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceScanService.class);

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final NamingStandardMapper namingStandardMapper;
    private final FieldTypeStandardMapper fieldTypeStandardMapper;
    private final ComplianceCheckResultMapper complianceCheckResultMapper;

    public ComplianceScanService(MetadataTableMapper metadataTableMapper,
                                 MetadataColumnMapper metadataColumnMapper,
                                 NamingStandardMapper namingStandardMapper,
                                 FieldTypeStandardMapper fieldTypeStandardMapper,
                                 ComplianceCheckResultMapper complianceCheckResultMapper) {
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.namingStandardMapper = namingStandardMapper;
        this.fieldTypeStandardMapper = fieldTypeStandardMapper;
        this.complianceCheckResultMapper = complianceCheckResultMapper;
    }

    /**
     * 全量合规扫描（对齐 ComplianceCheckService.check 的 datasourceIds 全量分支）：
     * 取全部在线数据源 → 删除范围内既有结果 → 按启用命名规范 + 字段类型标准逐表逐字段比对
     * → 批量插入新结果。
     *
     * @return 新增不合规结果数
     */
    @Transactional
    public int runFullScan() {
        // 不指定范围：取 metadata_table 中所有在线数据源 ID，命名 + 字段类型均检查
        List<Long> datasourceIds = allOnlineDatasourceIds();

        deleteExistingResults(datasourceIds, null, null);

        List<NamingStandard> tableStandards = namingStandardMapper.selectEnabledByAppliesTo("TABLE");
        List<NamingStandard> columnStandards = namingStandardMapper.selectEnabledByAppliesTo("COLUMN");
        List<Long> standardIds = Stream.concat(tableStandards.stream(), columnStandards.stream())
                .map(NamingStandard::getTargetStandardId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, FieldTypeStandard> standardMap = !standardIds.isEmpty()
                ? fieldTypeStandardMapper.selectBatchIds(standardIds)
                .stream().collect(Collectors.toMap(FieldTypeStandard::getId, s -> s))
                : Map.of();

        List<ComplianceCheckResult> results = new ArrayList<>();
        LocalDateTime checkedAt = LocalDateTime.now();

        if (!datasourceIds.isEmpty()) {
            for (Long datasourceId : datasourceIds) {
                List<MetadataTable> tables = listTables(datasourceId, null, null);
                if (!tables.isEmpty()) {
                    checkTables(tables, tableStandards, columnStandards, standardMap, results, checkedAt);
                }
            }
        }

        // 批量插入检查结果，避免数千条结果逐条 insert
        if (!results.isEmpty()) {
            complianceCheckResultMapper.insertBatch(results);
        }
        return results.size();
    }

    private List<Long> allOnlineDatasourceIds() {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("source_status", MetadataSourceStatus.ONLINE.getCode());
        wrapper.select("distinct datasource_id");
        List<Object> ids = metadataTableMapper.selectObjs(wrapper);
        return ids.stream()
                .filter(Objects::nonNull)
                .map(o -> ((Number) o).longValue())
                .distinct()
                .collect(Collectors.toList());
    }

    private void deleteExistingResults(List<Long> datasourceIds, String databaseName, String schemaName) {
        QueryWrapper<ComplianceCheckResult> del = new QueryWrapper<>();
        if (!datasourceIds.isEmpty()) {
            del.in("datasource_id", datasourceIds);
        }
        if (!isBlank(databaseName)) {
            del.eq("database_name", databaseName);
        }
        if (!isBlank(schemaName)) {
            del.eq("schema_name", schemaName);
        }
        complianceCheckResultMapper.delete(del);
    }

    /**
     * 批量检查一组表：一次性查出所有字段并按表分组，避免逐表 selectByTableId 的 N+1 查询。
     */
    private void checkTables(List<MetadataTable> tables, List<NamingStandard> tableStandards,
                             List<NamingStandard> columnStandards,
                             Map<Long, FieldTypeStandard> standardMap,
                             List<ComplianceCheckResult> results, LocalDateTime checkedAt) {
        List<Long> tableIds = tables.stream().map(MetadataTable::getId).collect(Collectors.toList());
        Map<Long, List<MetadataColumn>> columnsByTableId = metadataColumnMapper.selectList(
                        new QueryWrapper<MetadataColumn>()
                                .in("table_id", tableIds)
                                .eq("source_status", MetadataSourceStatus.ONLINE.getCode())
                                .orderByAsc("ordinal_position")
                                .orderByAsc("column_name"))
                .stream()
                .collect(Collectors.groupingBy(MetadataColumn::getTableId));
        for (MetadataTable table : tables) {
            checkTable(table, tableStandards, results, checkedAt);
            for (MetadataColumn column : columnsByTableId.getOrDefault(table.getId(), List.of())) {
                checkColumn(column, table, columnStandards, standardMap, results, checkedAt);
            }
        }
    }

    private List<MetadataTable> listTables(Long datasourceId, String databaseName, String schemaName) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("source_status", MetadataSourceStatus.ONLINE.getCode());
        if (datasourceId != null) {
            wrapper.eq("datasource_id", datasourceId);
        }
        if (!isBlank(databaseName)) {
            wrapper.eq("database_name", databaseName);
        }
        if (!isBlank(schemaName)) {
            wrapper.eq("schema_name", schemaName);
        }
        return metadataTableMapper.selectList(wrapper);
    }

    private void checkTable(MetadataTable table, List<NamingStandard> standards,
                            List<ComplianceCheckResult> results, LocalDateTime checkedAt) {
        NamingStandard matched = matchFirst(table.getTableName(), standards);
        if (matched != null) {
            return;
        }
        results.add(buildResult(table, null, "TABLE", null, null, null, "NAMING", checkedAt,
                toApplicableStandards(standards, null)));
    }

    private void checkColumn(MetadataColumn column, MetadataTable table, List<NamingStandard> standards,
                             Map<Long, FieldTypeStandard> standardMap,
                             List<ComplianceCheckResult> results, LocalDateTime checkedAt) {
        NamingStandard matched = matchFirst(column.getColumnName(), standards);
        if (matched == null) {
            results.add(buildResult(table, column, "COLUMN", null, null, null, "NAMING", checkedAt,
                    toApplicableStandards(standards, null)));
            return;
        }
        FieldTypeStandard target = standardMap.get(matched.getTargetStandardId());
        if (target == null || target.getAllowedTypes() == null) {
            return;
        }
        String expected = String.join(",", target.getAllowedTypes());
        String actualType = column.getDataType();
        boolean typeCompliant = target.getAllowedTypes().stream()
                .anyMatch(t -> typeMatches(t, actualType));
        if (!typeCompliant) {
            results.add(buildResult(table, column, "COLUMN", matched, actualType, expected, "TYPE", checkedAt,
                    toApplicableStandards(List.of(matched), target)));
        }
    }

    private boolean typeMatches(String expected, String actual) {
        TypeInfo e = parseType(expected);
        TypeInfo a = parseType(actual);
        if (e == null || a == null) {
            return false;
        }
        // 基础类型必须一致（忽略末尾 INT/BIGINT 等通过 family 精确区分）
        if (!e.base.equals(a.base)) {
            return false;
        }
        // VARCHAR：要求实际长度 <= 标准长度
        if ("VARCHAR".equals(e.base) || "CHAR".equals(e.base)) {
            return e.length >= 0 && a.length >= 0 && e.length >= a.length;
        }
        // DECIMAL：要求精度/标度均不小于标准
        if ("DECIMAL".equals(e.base)) {
            return e.precision >= 0 && a.precision >= 0 && e.precision >= a.precision
                    && e.scale >= 0 && a.scale >= 0 && e.scale >= a.scale;
        }
        return true;
    }

    private TypeInfo parseType(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\w+)(?:\\(([^)]+)\\))?$")
                .matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        TypeInfo info = new TypeInfo();
        info.base = m.group(1);
        String params = m.group(2);
        if (params != null && !params.isBlank()) {
            String[] parts = params.split(",");
            try {
                if ("VARCHAR".equals(info.base) || "CHAR".equals(info.base)) {
                    info.length = Integer.parseInt(parts[0].trim());
                } else if ("DECIMAL".equals(info.base) || "NUMERIC".equals(info.base)) {
                    info.precision = Integer.parseInt(parts[0].trim());
                    if (parts.length > 1) {
                        info.scale = Integer.parseInt(parts[1].trim());
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return info;
    }

    private static class TypeInfo {
        String base;
        int length = Integer.MAX_VALUE;
        int precision = Integer.MAX_VALUE;
        int scale = Integer.MAX_VALUE;
    }

    private ComplianceCheckResult buildResult(MetadataTable table, MetadataColumn column, String objectType,
                                              NamingStandard standard, String actualValue, String expectedValue,
                                              String violationType, LocalDateTime checkedAt,
                                              List<ComplianceCheckResult.ApplicableStandard> applicableStandards) {
        ComplianceCheckResult r = new ComplianceCheckResult();
        // 自定义 insertBatch 不会触发 ASSIGN_ID，需提前生成主键
        r.setId(IdWorker.getId());
        r.setDatasourceId(table.getDatasourceId());
        r.setDatabaseName(table.getDatabaseName());
        r.setSchemaName(table.getSchemaName());
        r.setStandardId(standard != null ? standard.getId() : null);
        r.setStandardName(standard != null ? standard.getName() : "未命中命名规范");
        r.setObjectType(objectType);
        r.setTableId(table.getId());
        r.setColumnId(column != null ? column.getId() : null);
        r.setObjectName(column != null ? column.getColumnName() : table.getTableName());
        r.setObjectPath(buildObjectPath(table, column));
        r.setViolationType(violationType);
        r.setActualValue(actualValue);
        r.setExpectedValue(expectedValue);
        r.setApplicableStandards(applicableStandards);
        r.setIsCompliant(0);
        r.setCheckedAt(checkedAt);
        r.setIgnored(0);
        return r;
    }

    private List<ComplianceCheckResult.ApplicableStandard> toApplicableStandards(
            List<NamingStandard> standards, FieldTypeStandard fieldTypeStandard) {
        return standards.stream().map(s -> {
            ComplianceCheckResult.ApplicableStandard item = new ComplianceCheckResult.ApplicableStandard();
            item.setStandardName(s.getName());
            item.setRuleType(s.getRuleType());
            item.setRuleValue(s.getRuleValue());
            item.setAllowedTypes(fieldTypeStandard != null ? fieldTypeStandard.getAllowedTypes() : null);
            return item;
        }).collect(Collectors.toList());
    }

    private String buildObjectPath(MetadataTable table, MetadataColumn column) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(table.getDatabaseName())) {
            sb.append(table.getDatabaseName());
        }
        if (!isBlank(table.getSchemaName())) {
            if (sb.length() > 0) {
                sb.append(".");
            }
            sb.append(table.getSchemaName());
        }
        if (sb.length() > 0) {
            sb.append(".");
        }
        sb.append(table.getTableName());
        if (column != null) {
            sb.append(".").append(column.getColumnName());
        }
        return sb.toString();
    }

    private NamingStandard matchFirst(String objectName, List<NamingStandard> standards) {
        for (NamingStandard standard : standards) {
            if (match(objectName, standard)) {
                return standard;
            }
        }
        return null;
    }

    private boolean match(String objectName, NamingStandard standard) {
        String ruleValue = standard.getRuleValue();
        if (ruleValue == null || ruleValue.isBlank()) {
            return false;
        }
        return switch (standard.getRuleType()) {
            case "PREFIX" -> objectName.startsWith(ruleValue);
            case "SUFFIX" -> objectName.endsWith(ruleValue);
            case "REGEX" -> {
                try {
                    yield Pattern.compile(ruleValue).matcher(objectName).matches();
                } catch (PatternSyntaxException e) {
                    log.warn("命名规范正则表达式语法错误，standard={}, ruleValue={}", standard.getName(), ruleValue, e);
                    yield false;
                }
            }
            default -> false;
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
