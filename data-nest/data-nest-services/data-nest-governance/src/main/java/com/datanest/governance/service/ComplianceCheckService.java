package com.datanest.governance.service;

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
import com.datanest.task.core.dto.ComplianceCheckSummaryDTO;
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
import com.datanest.governance.util.CsvExportHelper;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
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
        // ignored：null 或缺省=0（默认仅未忽略）；1=仅已忽略；2=全部（不过滤）
        if (ignored != null && ignored == 2) {
            // 全部：不加 ignored 条件
        } else {
            int effectiveIgnored = ignored == null ? 0 : ignored;
            wrapper.eq("ignored", effectiveIgnored);
        }
        if (request.getViolationType() != null && !request.getViolationType().isBlank()) {
            wrapper.eq("violation_type", request.getViolationType());
        }
        wrapper.orderByDesc("checked_at").orderByAsc("id");
        IPage<ComplianceCheckResult> result = complianceCheckResultMapper.selectPage(page, wrapper);
        List<ComplianceCheckResultDTO> records = result.getRecords().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * Sprint6 新增：统计摘要（前端三格统计：不合规项 / 已忽略 / 合规率）。
     * 口径：nonCompliant=范围内未忽略不合规项（ignored=0），ignored=已忽略数（ignored=1），
     * totalObjects=范围内在线表+字段对象总数，complianceRate=(1 - nonCompliant/totalObjects)*100 保留 1 位小数。
     */
    public ComplianceCheckSummaryDTO summary(ComplianceCheckRequest request) {
        Long tableId = request.getTableId();
        List<Long> tableIds = resolveRangeTableIds(request);
        // 范围内在线对象总数（表 + 字段）作为合规率分母；空范围视为合规率 100
        long totalObjects = 0L;
        if (tableId != null) {
            QueryWrapper<MetadataColumn> cw = new QueryWrapper<>();
            cw.eq("table_id", tableId).eq("source_status", MetadataSourceStatus.ONLINE.getCode());
            totalObjects = 1L + metadataColumnMapper.selectCount(cw);
        } else if (!tableIds.isEmpty()) {
            QueryWrapper<MetadataColumn> cw = new QueryWrapper<>();
            cw.in("table_id", tableIds).eq("source_status", MetadataSourceStatus.ONLINE.getCode());
            totalObjects = tableIds.size() + metadataColumnMapper.selectCount(cw);
        }

        // 范围内未忽略/已忽略不合规项数（一次表 ID 解析复用，避免重复查询）
        long nonCompliant = 0L;
        long ignored = 0L;
        if (!tableIds.isEmpty()) {
            QueryWrapper<ComplianceCheckResult> nc = new QueryWrapper<>();
            nc.in("table_id", tableIds).eq("ignored", 0);
            nonCompliant = complianceCheckResultMapper.selectCount(nc);
            QueryWrapper<ComplianceCheckResult> ig = new QueryWrapper<>();
            ig.in("table_id", tableIds).eq("ignored", 1);
            ignored = complianceCheckResultMapper.selectCount(ig);
        }

        double rate = totalObjects == 0 ? 100.0
                : Math.round((1 - (double) nonCompliant / totalObjects) * 1000) / 10.0;

        ComplianceCheckSummaryDTO dto = new ComplianceCheckSummaryDTO();
        dto.setNonCompliant(nonCompliant);
        dto.setIgnored(ignored);
        dto.setTotalObjects(totalObjects);
        dto.setComplianceRate(rate);
        return dto;
    }

    /**
     * 解析范围内在线表 ID 列表（tableId 模式返回单元素；否则按数据源/库/模式解析）。
     * 供 summary 统计复用，避免 buildRangeWrapper 多次触发表 ID 查询。
     */
    private List<Long> resolveRangeTableIds(ComplianceCheckRequest request) {
        Long tableId = request.getTableId();
        if (tableId != null) {
            if (metadataTableMapper.selectById(tableId) == null) {
                return List.of();
            }
            return List.of(tableId);
        }
        List<Long> datasourceIds = resolveDatasourceIds(request, tableId);
        if (datasourceIds.isEmpty()) {
            return List.of();
        }
        return listTableIds(datasourceIds, request.getDatabaseName(), request.getSchemaName());
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
    public void export(ComplianceCheckRequest request, OutputStream out) throws IOException {
        List<ComplianceCheckResult> list = queryByRange(request, 0);
        // CSVPrinter 负责转义/引号；BOM 与公式注入防护由 CsvExportHelper 统一处理（只 flush 不 close）
        CSVPrinter printer = CsvExportHelper.printer(out);
        printer.printRecord("对象路径", "对象类型", "违规类型", "实际值", "期望值", "适用规范", "检查时间", "是否忽略");
        for (ComplianceCheckResult r : list) {
            printer.printRecord(CsvExportHelper.safe(r.getObjectPath()),
                    CsvExportHelper.safe(r.getObjectType()),
                    CsvExportHelper.safe(r.getViolationType()),
                    CsvExportHelper.safe(r.getActualValue()),
                    CsvExportHelper.safe(r.getExpectedValue()),
                    CsvExportHelper.safe(applicableNames(r)),
                    r.getCheckedAt() == null ? "" : r.getCheckedAt().toString(),
                    Integer.valueOf(1).equals(r.getIgnored()) ? "是" : "否");
        }
        printer.flush();
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
