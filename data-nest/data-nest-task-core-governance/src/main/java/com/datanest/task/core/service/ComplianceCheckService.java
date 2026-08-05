package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.task.core.dto.ComplianceCheckPageRequest;
import com.datanest.task.core.dto.ComplianceCheckRequest;
import com.datanest.task.core.dto.ComplianceCheckResultDTO;
import com.datanest.task.core.entity.ComplianceCheckResult;
import com.datanest.task.core.entity.FieldTypeStandard;
import com.datanest.task.core.entity.MetadataColumn;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.NamingStandard;
import com.datanest.task.core.mapper.ComplianceCheckResultMapper;
import com.datanest.task.core.mapper.FieldTypeStandardMapper;
import com.datanest.task.core.mapper.MetadataColumnMapper;
import com.datanest.task.core.mapper.MetadataTableMapper;
import com.datanest.task.core.mapper.NamingStandardMapper;
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
 * 标准合规检查服务（治理编排域）。
 * 从 governance 模块下沉至 task-core-governance，供 governance Controller 与 job 定时扫描共用。
 * Sprint6 扩展：ignore/unignore/page 分页/export CSV。
 */
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
        String databaseName = request.getDatabaseName();
        String schemaName = request.getSchemaName();

        boolean checkNaming = request.getCheckNaming() == null || request.getCheckNaming();
        boolean checkFieldType = request.getCheckFieldType() == null || request.getCheckFieldType();
        if (!checkNaming && !checkFieldType) {
            throw new BusinessException(ErrorCode.COMPLIANCE_CHECK_ITEM_REQUIRED);
        }

        List<Long> datasourceIds = resolveDatasourceIds(request, tableId);

        deleteExistingResults(request, datasourceIds);

        List<NamingStandard> tableStandards = checkNaming
                ? namingStandardMapper.selectEnabledByAppliesTo("TABLE")
                : List.of();
        List<NamingStandard> columnStandards = checkNaming
                ? namingStandardMapper.selectEnabledByAppliesTo("COLUMN")
                : List.of();
        List<Long> standardIds = Stream.concat(tableStandards.stream(), columnStandards.stream())
                .map(NamingStandard::getTargetStandardId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, FieldTypeStandard> standardMap = checkFieldType && !standardIds.isEmpty()
                ? fieldTypeStandardMapper.selectBatchIds(standardIds)
                .stream().collect(Collectors.toMap(FieldTypeStandard::getId, s -> s))
                : Map.of();

        List<ComplianceCheckResult> results = new ArrayList<>();
        LocalDateTime checkedAt = LocalDateTime.now();

        if (tableId != null) {
            checkOneTable(tableId, tableStandards, columnStandards, standardMap, results, checkedAt);
        } else if (!datasourceIds.isEmpty()) {
            for (Long datasourceId : datasourceIds) {
                List<MetadataTable> tables = listTables(datasourceId, databaseName, schemaName);
                if (!tables.isEmpty()) {
                    checkTables(tables, tableStandards, columnStandards, standardMap, results, checkedAt);
                }
            }
        }

        // 批量插入检查结果，避免数千条结果逐条 insert
        if (!results.isEmpty()) {
            complianceCheckResultMapper.insertBatch(results);
        }
        return results.stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 解析检查范围。若请求未指定数据源/表，则视为「全部数据源」（取 metadata_table 中所有在线数据源 ID）。
     */
    private List<Long> resolveDatasourceIds(ComplianceCheckRequest request, Long tableId) {
        if (tableId != null) {
            return List.of();
        }
        if (request.getDatasourceIds() != null && !request.getDatasourceIds().isEmpty()) {
            return new ArrayList<>(request.getDatasourceIds());
        }
        if (request.getDatasourceId() != null) {
            return List.of(request.getDatasourceId());
        }
        return allOnlineDatasourceIds();
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
        checkTables(List.of(table), tableStandards, columnStandards, standardMap, results, checkedAt);
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

    /**
     * 保留旧接口：按范围返回全量结果（含已忽略项）。
     */
    public List<ComplianceCheckResultDTO> listResults(ComplianceCheckRequest request) {
        List<ComplianceCheckResult> list = queryByRange(request, null);
        return list.stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * Sprint6 新增：分页查询结果。
     * 默认排除已忽略（ignored=0）；显式传 ignored 可筛选 0（未忽略）/1（已忽略）。
     */
    public PageResult<ComplianceCheckResultDTO> page(ComplianceCheckPageRequest request) {
        Integer ignored = request.getIgnored();
        int pageNo = request.getPage() == null ? 1 : request.getPage();
        int pageSize = request.getPageSize() == null ? 10 : request.getPageSize();
        IPage<ComplianceCheckResult> page = new Page<>(pageNo, pageSize);
        QueryWrapper<ComplianceCheckResult> wrapper = buildRangeWrapper(request);
        // 默认排除已忽略
        int effectiveIgnored = ignored == null ? 0 : ignored;
        wrapper.eq("ignored", effectiveIgnored);
        wrapper.orderByDesc("checked_at").orderByAsc("id");
        IPage<ComplianceCheckResult> result = complianceCheckResultMapper.selectPage(page, wrapper);
        List<ComplianceCheckResultDTO> records = result.getRecords().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * Sprint6 新增：忽略某条不合规项。
     *
     * @param operatorId 操作人 sys_user.id（由 Controller 从登录态获取）
     */
    @Transactional
    public void ignore(Long resultId, Long operatorId) {
        ComplianceCheckResult entity = complianceCheckResultMapper.selectById(resultId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.COMPLIANCE_CHECK_RESULT_NOT_FOUND);
        }
        if (Integer.valueOf(1).equals(entity.getIgnored())) {
            return;
        }
        UpdateWrapper<ComplianceCheckResult> uw = new UpdateWrapper<>();
        uw.eq("id", resultId)
                .set("ignored", 1)
                .set("ignored_at", LocalDateTime.now())
                .set("ignored_by", operatorId);
        complianceCheckResultMapper.update(null, uw);
    }

    /**
     * Sprint6 新增：取消忽略某条不合规项。
     */
    @Transactional
    public void unignore(Long resultId) {
        ComplianceCheckResult entity = complianceCheckResultMapper.selectById(resultId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.COMPLIANCE_CHECK_RESULT_NOT_FOUND);
        }
        if (!Integer.valueOf(1).equals(entity.getIgnored())) {
            return;
        }
        UpdateWrapper<ComplianceCheckResult> uw = new UpdateWrapper<>();
        uw.eq("id", resultId)
                .set("ignored", 0)
                .set("ignored_at", null)
                .set("ignored_by", null);
        complianceCheckResultMapper.update(null, uw);
    }

    /**
     * Sprint6 新增：导出问题清单 CSV（UTF-8 with BOM，兼容 Excel）。
     * 默认导出未忽略的问题（与 page 的「默认排除已忽略」语义一致）。
     */
    public String export(ComplianceCheckRequest request) {
        List<ComplianceCheckResult> list = queryByRange(request, 0);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("对象路径,对象类型,违规类型,实际值,期望值,适用规范,检查时间,是否忽略\n");
        for (ComplianceCheckResult r : list) {
            sb.append(esc(r.getObjectPath())).append(',')
                    .append(esc(r.getObjectType())).append(',')
                    .append(esc(r.getViolationType())).append(',')
                    .append(esc(r.getActualValue())).append(',')
                    .append(esc(r.getExpectedValue())).append(',')
                    .append(esc(applicableNames(r))).append(',')
                    .append(r.getCheckedAt() == null ? "" : r.getCheckedAt().toString()).append(',')
                    .append(Integer.valueOf(1).equals(r.getIgnored()) ? "是" : "否")
                    .append('\n');
        }
        return sb.toString();
    }

    private List<ComplianceCheckResult> queryByRange(ComplianceCheckRequest request, Integer ignored) {
        QueryWrapper<ComplianceCheckResult> wrapper = buildRangeWrapper(request);
        if (ignored != null) {
            wrapper.eq("ignored", ignored);
        }
        wrapper.orderByDesc("checked_at");
        return complianceCheckResultMapper.selectList(wrapper);
    }

    private QueryWrapper<ComplianceCheckResult> buildRangeWrapper(ComplianceCheckRequest request) {
        QueryWrapper<ComplianceCheckResult> wrapper = new QueryWrapper<>();
        Long tableId = request.getTableId();
        if (tableId != null) {
            wrapper.eq("table_id", tableId);
        } else {
            List<Long> datasourceIds = resolveDatasourceIds(request, tableId);
            if (datasourceIds.isEmpty()) {
                // 无条件约束，返回空（与旧 listResults 语义一致）
                wrapper.eq("1", "0");
                return wrapper;
            }
            List<Long> tableIds = listTableIds(datasourceIds, request.getDatabaseName(), request.getSchemaName());
            if (!tableIds.isEmpty()) {
                wrapper.in("table_id", tableIds);
            } else {
                wrapper.eq("1", "0");
                return wrapper;
            }
        }
        if (request.getStartTime() != null) {
            wrapper.ge("checked_at", request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le("checked_at", request.getEndTime());
        }
        return wrapper;
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

    private List<Long> listTableIds(List<Long> datasourceIds, String databaseName, String schemaName) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("source_status", MetadataSourceStatus.ONLINE.getCode());
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

    private String applicableNames(ComplianceCheckResult r) {
        if (r.getApplicableStandards() == null || r.getApplicableStandards().isEmpty()) {
            return "";
        }
        return r.getApplicableStandards().stream()
                .map(ComplianceCheckResult.ApplicableStandard::getStandardName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(";"));
    }

    private String esc(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
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
        dto.setApplicableStandards(toDtoApplicableStandards(entity.getApplicableStandards()));
        dto.setIsCompliant(entity.getIsCompliant());
        dto.setCheckedAt(entity.getCheckedAt());
        dto.setIgnored(entity.getIgnored());
        dto.setIgnoredAt(entity.getIgnoredAt());
        dto.setIgnoredBy(entity.getIgnoredBy());
        return dto;
    }

    private List<ComplianceCheckResultDTO.ApplicableStandardDTO> toDtoApplicableStandards(
            List<ComplianceCheckResult.ApplicableStandard> list) {
        if (list == null) {
            return null;
        }
        return list.stream().map(s -> {
            ComplianceCheckResultDTO.ApplicableStandardDTO dto = new ComplianceCheckResultDTO.ApplicableStandardDTO();
            dto.setStandardName(s.getStandardName());
            dto.setRuleType(s.getRuleType());
            dto.setRuleValue(s.getRuleValue());
            dto.setAllowedTypes(s.getAllowedTypes());
            return dto;
        }).collect(Collectors.toList());
    }
}
