package com.datanest.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.governance.dto.ComplianceCheckRequest;
import com.datanest.governance.dto.ComplianceCheckResultDTO;
import com.datanest.governance.entity.*;
import com.datanest.governance.mapper.*;
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

@Service
public class ComplianceCheckService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceCheckService.class);

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final NamingStandardMapper namingStandardMapper;
    private final FieldTypeStandardMapper fieldTypeStandardMapper;
    private final ComplianceCheckResultMapper complianceCheckResultMapper;

    public ComplianceCheckService(MetadataTableMapper metadataTableMapper,
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

    @Transactional
    public List<ComplianceCheckResultDTO> check(ComplianceCheckRequest request) {
        Long tableId = request.getTableId();
        List<Long> datasourceIds = resolveDatasourceIds(request);
        String databaseName = request.getDatabaseName();
        String schemaName = request.getSchemaName();

        if (tableId == null && datasourceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "合规检查请求必须指定数据源或表范围，不能单独使用库/Schema");
        }

        deleteExistingResults(request, datasourceIds);

        List<NamingStandard> tableStandards = namingStandardMapper.selectEnabledByAppliesTo("TABLE");
        List<NamingStandard> columnStandards = namingStandardMapper.selectEnabledByAppliesTo("COLUMN");
        Map<Long, FieldTypeStandard> standardMap = fieldTypeStandardMapper.selectBatchIds(
                Stream.concat(tableStandards.stream(), columnStandards.stream())
                        .map(NamingStandard::getTargetStandardId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList())
        ).stream().collect(Collectors.toMap(FieldTypeStandard::getId, s -> s));

        List<ComplianceCheckResult> results = new ArrayList<>();
        LocalDateTime checkedAt = LocalDateTime.now();

        if (tableId != null) {
            checkOneTable(tableId, tableStandards, columnStandards, standardMap, results, checkedAt);
        } else {
            for (Long datasourceId : datasourceIds) {
                List<MetadataTable> tables = listTables(datasourceId, databaseName, schemaName);
                for (MetadataTable table : tables) {
                    checkTable(table, tableStandards, results, checkedAt);
                    List<MetadataColumn> columns = metadataColumnMapper.selectByTableId(table.getId());
                    for (MetadataColumn column : columns) {
                        checkColumn(column, table, columnStandards, standardMap, results, checkedAt);
                    }
                }
            }
        }

        for (ComplianceCheckResult r : results) {
            complianceCheckResultMapper.insert(r);
        }
        return results.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private List<Long> resolveDatasourceIds(ComplianceCheckRequest request) {
        if (request.getDatasourceIds() != null && !request.getDatasourceIds().isEmpty()) {
            return new ArrayList<>(request.getDatasourceIds());
        }
        if (request.getDatasourceId() != null) {
            return List.of(request.getDatasourceId());
        }
        return List.of();
    }

    private void deleteExistingResults(ComplianceCheckRequest request, List<Long> datasourceIds) {
        QueryWrapper<ComplianceCheckResult> del = new QueryWrapper<>();
        if (request.getTableId() != null) {
            del.eq("table_id", request.getTableId());
        }
        if (!datasourceIds.isEmpty()) {
            del.in("datasource_id", datasourceIds);
        }
        if (!isBlank(request.getDatabaseName())) {
            del.eq("database_name", request.getDatabaseName());
        }
        if (!isBlank(request.getSchemaName())) {
            del.eq("schema_name", request.getSchemaName());
        }
        complianceCheckResultMapper.delete(del);
    }

    private void checkOneTable(Long tableId, List<NamingStandard> tableStandards,
                               List<NamingStandard> columnStandards,
                               Map<Long, FieldTypeStandard> standardMap,
                               List<ComplianceCheckResult> results, LocalDateTime checkedAt) {
        MetadataTable table = metadataTableMapper.selectById(tableId);
        if (table == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        checkTable(table, tableStandards, results, checkedAt);
        List<MetadataColumn> columns = metadataColumnMapper.selectByTableId(tableId);
        for (MetadataColumn column : columns) {
            checkColumn(column, table, columnStandards, standardMap, results, checkedAt);
        }
    }

    public List<ComplianceCheckResultDTO> listResults(ComplianceCheckRequest request) {
        QueryWrapper<ComplianceCheckResult> wrapper = new QueryWrapper<>();
        if (request.getTableId() != null) {
            wrapper.eq("table_id", request.getTableId());
        } else {
            List<Long> datasourceIds = resolveDatasourceIds(request);
            if (!datasourceIds.isEmpty()) {
                List<Long> tableIds = listTableIds(datasourceIds, request.getDatabaseName(), request.getSchemaName());
                if (!tableIds.isEmpty()) {
                    wrapper.in("table_id", tableIds);
                } else {
                    return List.of();
                }
            }
        }
        wrapper.orderByDesc("checked_at");
        List<ComplianceCheckResult> list = complianceCheckResultMapper.selectList(wrapper);
        return list.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private List<MetadataTable> listTables(Long datasourceId, String databaseName, String schemaName) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
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

    private List<Long> listTableIds(Long datasourceId, String databaseName, String schemaName) {
        return listTables(datasourceId, databaseName, schemaName).stream()
                .map(MetadataTable::getId)
                .collect(Collectors.toList());
    }

    private List<Long> listTableIds(List<Long> datasourceIds, String databaseName, String schemaName) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        if (datasourceIds != null && !datasourceIds.isEmpty()) {
            wrapper.in("datasource_id", datasourceIds);
        }
        if (!isBlank(databaseName)) {
            wrapper.eq("database_name", databaseName);
        }
        if (!isBlank(schemaName)) {
            wrapper.eq("schema_name", schemaName);
        }
        return metadataTableMapper.selectList(wrapper).stream()
                .map(MetadataTable::getId)
                .collect(Collectors.toList());
    }

    private void checkTable(MetadataTable table, List<NamingStandard> standards,
                            List<ComplianceCheckResult> results, LocalDateTime checkedAt) {
        NamingStandard matched = matchFirst(table.getTableName(), standards);
        if (matched != null) {
            return;
        }
        results.add(buildResult(table, null, "TABLE", null, null, null, "NAMING", checkedAt));
    }

    private void checkColumn(MetadataColumn column, MetadataTable table, List<NamingStandard> standards,
                             Map<Long, FieldTypeStandard> standardMap,
                             List<ComplianceCheckResult> results, LocalDateTime checkedAt) {
        NamingStandard matched = matchFirst(column.getColumnName(), standards);
        if (matched == null) {
            results.add(buildResult(table, column, "COLUMN", null, null, null, "NAMING", checkedAt));
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
            results.add(buildResult(table, column, "COLUMN", matched, actualType, expected, "TYPE", checkedAt));
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
                                              String violationType, LocalDateTime checkedAt) {
        ComplianceCheckResult r = new ComplianceCheckResult();
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
        r.setIsCompliant(0);
        r.setCheckedAt(checkedAt);
        return r;
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

    private ComplianceCheckResultDTO toDTO(ComplianceCheckResult entity) {
        ComplianceCheckResultDTO dto = new ComplianceCheckResultDTO();
        dto.setId(entity.getId());
        dto.setStandardId(entity.getStandardId());
        dto.setStandardName(entity.getStandardName());
        dto.setObjectType(entity.getObjectType());
        dto.setObjectPath(entity.getObjectPath());
        dto.setViolationType(entity.getViolationType());
        dto.setTableId(entity.getTableId());
        dto.setColumnId(entity.getColumnId());
        dto.setObjectName(entity.getObjectName());
        dto.setActualValue(entity.getActualValue());
        dto.setExpectedValue(entity.getExpectedValue());
        dto.setIsCompliant(entity.getIsCompliant());
        dto.setCheckedAt(entity.getCheckedAt());
        return dto;
    }
}
