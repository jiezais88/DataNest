package com.datanest.dataservice.service;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.auth.DataPermissionMatcher;
import com.datanest.common.constant.DorisConstants;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.common.model.UserDataPermissionDTO;
import com.datanest.dataservice.dto.ApiKeyBriefDTO;
import com.datanest.dataservice.dto.ApiParamDef;
import com.datanest.dataservice.dto.CustomSqlParamDef;
import com.datanest.dataservice.dto.DataApiCreateRequest;
import com.datanest.dataservice.dto.DataApiDefinition;
import com.datanest.dataservice.dto.DataApiDetailDTO;
import com.datanest.dataservice.dto.DataApiDocDTO;
import com.datanest.dataservice.dto.DataApiPageItem;
import com.datanest.dataservice.dto.DataApiSummaryDTO;
import com.datanest.dataservice.dto.DataApiUpdateRequest;
import com.datanest.dataservice.dto.RefCount;
import com.datanest.dataservice.entity.ApiKeyBinding;
import com.datanest.dataservice.entity.DataApi;
import com.datanest.dataservice.mapper.ApiCallLogMapper;
import com.datanest.dataservice.mapper.ApiKeyBindingMapper;
import com.datanest.dataservice.mapper.ApiKeyMapper;
import com.datanest.dataservice.mapper.DataApiMapper;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.governance.api.GovernanceMetadataApi;
import com.datanest.governance.api.MetadataWriteApi;
import com.datanest.governance.api.dto.LineageRecordBatchRequest;
import com.datanest.governance.api.dto.LineageRecordItemDTO;
import com.datanest.governance.api.dto.MetadataTableSensitivityDTO;
import com.datanest.system.api.SystemPermissionApi;
import com.datanest.system.api.SystemUserApi;
import com.datanest.task.core.config.DorisDataSourceConfig;
import com.datanest.task.core.support.DataPermissionResolver;
import com.datanest.task.core.support.SystemUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据 API 定义服务（Sprint 10 F2）：CRUD + 生命周期（发布/下线/软删）+ 敏感度闸门 + 自动文档。
 * <p>
 * 敏感度闸门（fail-closed，对齐 SQL 终端语义）：创建/编辑/发布前经 governance 批量查表敏感度——
 * PUBLIC 放行；INTERNAL 需超管开白（api_exempted=1）；CONFIDENTIAL 恒禁（9004）；
 * governance 不可达拒绝（9012），避免机密表因治理故障裸奔。
 */
@Service
public class DataApiService {

    private static final Logger logger = LoggerFactory.getLogger(DataApiService.class);

    private static final String CONFIDENTIAL = "CONFIDENTIAL";
    private static final String INTERNAL = "INTERNAL";

    /** 对外路径前缀（网关 /api/data-service/** 路由 StripPrefix=1 后的服务内路径） */
    private static final String PATH_PREFIX = "/open-api/v1/";

    /** 路径自定义段：小写字母数字开头，可含 - _，最长 100 */
    private static final Pattern PATH_SEGMENT_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-_]{0,99}$");

    /** 列标识符（filters/fields 白名单，防注入） */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** 排序白名单：列名 + 可选 ASC/DESC（order_by 会拼进 SQL，严格防注入） */
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}(\\s+(?i:ASC|DESC))?$");

    private static final int MAX_FILTERS = 20;
    private static final int MAX_FIELDS = 100;

    private final DataApiMapper dataApiMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyBindingMapper bindingMapper;
    private final ApiCallLogMapper callLogMapper;
    private final GovernanceMetadataApi governanceMetadataApi;
    private final MetadataWriteApi metadataWriteApi;
    private final EngineeringDatasourceApi datasourceApi;
    private final SystemUserApi systemUserApi;
    private final SystemPermissionApi systemPermissionApi;
    private final CustomSqlService customSqlService;

    public DataApiService(DataApiMapper dataApiMapper,
                          ApiKeyMapper apiKeyMapper,
                          ApiKeyBindingMapper bindingMapper,
                          ApiCallLogMapper callLogMapper,
                          GovernanceMetadataApi governanceMetadataApi,
                          MetadataWriteApi metadataWriteApi,
                          EngineeringDatasourceApi datasourceApi,
                          SystemUserApi systemUserApi,
                          SystemPermissionApi systemPermissionApi,
                          CustomSqlService customSqlService) {
        this.dataApiMapper = dataApiMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.bindingMapper = bindingMapper;
        this.callLogMapper = callLogMapper;
        this.governanceMetadataApi = governanceMetadataApi;
        this.metadataWriteApi = metadataWriteApi;
        this.datasourceApi = datasourceApi;
        this.systemUserApi = systemUserApi;
        this.systemPermissionApi = systemPermissionApi;
        this.customSqlService = customSqlService;
    }

    /**
     * 创建 API（Sprint 13 双形态）：TABLE_SELECT 走一期流程（选表 + filters/fields）；
     * CUSTOM_SQL 走只读校验 + 涉及表 fail-closed 闸门 + 参数校验 + 血缘（技术文档 §3.1）。
     */
    public DataApiDetailDTO create(DataApiCreateRequest request) {
        String path = normalizePath(request.getPath());
        String queryType = normalizeQueryType(request.getQueryType());
        validateDatasource(request.getDatasourceId());
        assertPathAvailable(path, null);

        DataApi api = new DataApi();
        api.setName(request.getName().trim());
        api.setPath(path);
        api.setMethod("GET");
        api.setDatasourceId(request.getDatasourceId());
        api.setQueryType(queryType);
        api.setPaginated(request.getPaginated() == null ? 1 : (request.getPaginated() == 0 ? 0 : 1));
        api.setPageSizeMax(normalizePageSizeMax(request.getPageSizeMax()));
        api.setStatus(DataApi.STATUS_CREATED);
        api.setDeleted(0);
        api.setCreatedBy(currentUserId());
        api.setCreatedAt(LocalDateTime.now());

        List<CustomSqlService.InvolvedTable> involvedTables = List.of();
        if (DataApi.QUERY_TYPE_CUSTOM_SQL.equals(queryType)) {
            involvedTables = applyCustomSqlDefinition(api, request);
        } else {
            applyTableSelectDefinition(api, request);
        }
        dataApiMapper.insert(api);
        if (DataApi.QUERY_TYPE_CUSTOM_SQL.equals(queryType)) {
            writeLineage(api, involvedTables);
        }
        logger.info("创建数据 API: id={}, path={}, queryType={}, table={}.{}", api.getId(), api.getPath(),
                api.getQueryType(), api.getDatabaseName(), api.getTableName());
        return detail(api.getId());
    }

    /**
     * 编辑 API：名称/路径/参数/字段/排序/分页/查询形态可改；数据源/库/表绑定不可改（换表 = 新建）。
     * 编辑前重新过敏感度闸门（表可能在创建后被改级）；CUSTOM_SQL 换 SQL 后重新校验 + 重新过闸门 + 更新血缘。
     */
    public DataApiDetailDTO update(Long id, DataApiUpdateRequest request) {
        DataApi api = loadApi(id);
        String path = normalizePath(request.getPath());
        String queryType = normalizeQueryType(request.getQueryType());
        assertPathAvailable(path, id);

        // UpdateWrapper 显式 set：order_by/sql_text/involved_tables 传空需写成 NULL（updateById 忽略 null 字段）
        UpdateWrapper<DataApi> wrapper = new UpdateWrapper<DataApi>()
                .eq("id", id)
                .set("name", request.getName().trim())
                .set("path", path)
                .set("query_type", queryType)
                .set("paginated", request.getPaginated() == null ? api.getPaginated()
                        : (request.getPaginated() == 0 ? 0 : 1))
                .set("page_size_max", request.getPageSizeMax() == null ? api.getPageSizeMax()
                        : normalizePageSizeMax(request.getPageSizeMax()))
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());

        List<CustomSqlService.InvolvedTable> involvedTables = List.of();
        if (DataApi.QUERY_TYPE_CUSTOM_SQL.equals(queryType)) {
            involvedTables = applyCustomSqlUpdate(api, request, wrapper);
        } else {
            // 切回选表形态时清理自定义 SQL 字段
            wrapper.set("sql_text", null).set("involved_tables", null);
            wrapper.set("params_json", JSON.toJSONString(buildDefinition(request.getFilters(), request.getFields())));
            wrapper.set("order_by", normalizeOrderBy(request.getOrderBy()));
            checkDataPermission(api.getDatasourceId(), api.getDatabaseName(), api.getTableName());
            checkSensitivityGate(api.getDatasourceId(), api.getDatabaseName(), api.getSchemaName(), api.getTableName());
        }
        dataApiMapper.update(null, wrapper);
        if (DataApi.QUERY_TYPE_CUSTOM_SQL.equals(queryType)) {
            writeLineage(api, involvedTables);
        }
        logger.info("编辑数据 API: id={}, path={}, queryType={}", id, path, queryType);
        return detail(id);
    }

    /**
     * 发布（CREATED/DISABLED → PUBLISHED，已发布幂等）：发布前重新过敏感度闸门。
     * CUSTOM_SQL 形态按 involved_tables 逐表重新过闸门（表可能在创建后被改级/降权，fail-closed）。
     */
    public void publish(Long id) {
        DataApi api = loadApi(id);
        if (DataApi.STATUS_PUBLISHED.equals(api.getStatus())) {
            return;
        }
        if (DataApi.QUERY_TYPE_CUSTOM_SQL.equals(api.getQueryType())) {
            recheckCustomSqlGates(api);
        } else {
            checkDataPermission(api.getDatasourceId(), api.getDatabaseName(), api.getTableName());
            checkSensitivityGate(api.getDatasourceId(), api.getDatabaseName(), api.getSchemaName(), api.getTableName());
        }
        api.setStatus(DataApi.STATUS_PUBLISHED);
        api.setUpdatedBy(currentUserId());
        api.setUpdatedAt(LocalDateTime.now());
        dataApiMapper.updateById(api);
        logger.info("发布数据 API: id={}, path={}, queryType={}", id, api.getPath(), api.getQueryType());
    }

    /**
     * 下线（PUBLISHED → DISABLED，已下线幂等）；下线后对外调用返回 404「API 已下线」。
     */
    public void disable(Long id) {
        DataApi api = loadApi(id);
        if (DataApi.STATUS_DISABLED.equals(api.getStatus())) {
            return;
        }
        api.setStatus(DataApi.STATUS_DISABLED);
        api.setUpdatedBy(currentUserId());
        api.setUpdatedAt(LocalDateTime.now());
        dataApiMapper.updateById(api);
        logger.info("下线数据 API: id={}, path={}", id, api.getPath());
    }

    /**
     * 按 metadata_table_id 批量下线已发布 API（机密改级联动，内部调用，无 Sa-Token 上下文）。
     * 仅对 PUBLISHED + deleted=0 的行操作；不设 updatedBy（系统操作）。
     * @return 实际下线数
     */
    public int disableByMetadataTableIds(List<Long> metadataTableIds) {
        if (metadataTableIds == null || metadataTableIds.isEmpty()) return 0;
        List<DataApi> apis = dataApiMapper.selectList(
                new QueryWrapper<DataApi>()
                        .in("metadata_table_id", metadataTableIds)
                        .eq("status", DataApi.STATUS_PUBLISHED)
                        .eq("deleted", 0));
        if (apis.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        for (DataApi api : apis) {
            api.setStatus(DataApi.STATUS_DISABLED);
            api.setUpdatedAt(now);
            dataApiMapper.updateById(api);
        }
        logger.info("机密改级联动下线 API: tables={}, disabled={}", metadataTableIds, apis.size());
        return apis.size();
    }

    /**
     * 删除（软删，PRD：删除保留调用统计）：deleted=1 释放 path 占用（部分唯一索引），并清理 Key 绑定关系。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DataApi api = loadApi(id);
        api.setDeleted(1);
        api.setUpdatedBy(currentUserId());
        api.setUpdatedAt(LocalDateTime.now());
        dataApiMapper.updateById(api);
        bindingMapper.delete(new QueryWrapper<ApiKeyBinding>().eq("api_id", id));
        logger.info("删除数据 API（软删）: id={}, path={}", id, api.getPath());
    }

    /**
     * 分页列表（scope=mine 仅看我创建的；keyword 匹配名称/路径；status 精确过滤；queryType 形态筛选）。
     */
    public PageResult<DataApiPageItem> page(long page, long pageSize, String scope, String keyword,
                                            String status, String queryType) {
        QueryWrapper<DataApi> wrapper = new QueryWrapper<DataApi>().eq("deleted", 0);
        if ("mine".equalsIgnoreCase(scope)) {
            wrapper.eq("created_by", currentUserId());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("name", kw).or().like("path", kw));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status.trim());
        }
        if (queryType != null && !queryType.isBlank()) {
            // 形态筛选：列存大写枚举，统一归一避免大小写不匹配（非法值自然查不到结果，不抛错）
            wrapper.eq("query_type", queryType.trim().toUpperCase());
        }
        wrapper.orderByDesc("created_at");
        Page<DataApi> p = dataApiMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100)), wrapper);

        List<DataApi> records = p.getRecords();
        List<Long> apiIds = records.stream().map(DataApi::getId).toList();
        Map<Long, Long> boundKeyCounts = apiIds.isEmpty() ? Map.of()
                : countMap(bindingMapper.countKeysByApiIds(apiIds));
        Map<Long, Long> calls7d = apiIds.isEmpty() ? Map.of()
                : countMap(callLogMapper.countCallsByApiIdsSince(apiIds, LocalDateTime.now().minusDays(7)));
        Map<Long, String> usernames = SystemUserResolver.usernames(systemUserApi,
                records.stream().flatMap(api -> java.util.stream.Stream.of(api.getCreatedBy(), api.getUpdatedBy()))
                        .filter(java.util.Objects::nonNull).distinct().toList());
        Map<Long, String> datasourceNames = datasourceNames(
                records.stream().map(DataApi::getDatasourceId).toList());
        Map<String, String> sensitivityMap = loadSensitivityLevels(records);

        List<DataApiPageItem> items = records.stream().map(api -> {
            DataApiPageItem item = new DataApiPageItem();
            item.setId(api.getId());
            item.setName(api.getName());
            item.setPath(api.getPath());
            item.setMethod(api.getMethod());
            item.setDatasourceId(api.getDatasourceId());
            item.setDatasourceName(datasourceNames.get(api.getDatasourceId()));
            item.setDatabaseName(api.getDatabaseName());
            item.setSchemaName(api.getSchemaName());
            item.setTableName(api.getTableName());
            item.setQueryType(api.getQueryType());
            item.setSensitivityLevel(sensitivityMap.get(sensitivityKey(
                    api.getDatasourceId(), api.getDatabaseName(), api.getSchemaName(), api.getTableName())));
            item.setStatus(api.getStatus());
            item.setBoundKeyCount(boundKeyCounts.getOrDefault(api.getId(), 0L));
            item.setCalls7d(calls7d.getOrDefault(api.getId(), 0L));
            item.setCreatedBy(api.getCreatedBy());
            item.setCreatedByName(usernames.get(api.getCreatedBy()));
            item.setCreatedAt(api.getCreatedAt());
            item.setUpdatedByName(usernames.get(api.getUpdatedBy()));
            item.setUpdatedAt(api.getUpdatedAt());
            return item;
        }).toList();
        return PageResult.of(items, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * API 详情：定义 + 自动文档 + 绑定 Key + 近 7 天调用。
     */
    public DataApiDetailDTO detail(Long id) {
        DataApi api = loadApi(id);
        DataApiDefinition definition = parseDefinition(api.getParamsJson());

        DataApiDetailDTO dto = new DataApiDetailDTO();
        dto.setId(api.getId());
        dto.setName(api.getName());
        dto.setPath(api.getPath());
        dto.setMethod(api.getMethod());
        dto.setDatasourceId(api.getDatasourceId());
        dto.setDatasourceName(datasourceNames(List.of(api.getDatasourceId())).get(api.getDatasourceId()));
        dto.setDatabaseName(api.getDatabaseName());
        dto.setSchemaName(api.getSchemaName());
        dto.setTableName(api.getTableName());
        dto.setSensitivityLevel(loadSensitivityLevels(List.of(api)).get(sensitivityKey(
                api.getDatasourceId(), api.getDatabaseName(), api.getSchemaName(), api.getTableName())));
        dto.setMetadataTableId(api.getMetadataTableId());
        dto.setDefinition(definition);
        dto.setQueryType(api.getQueryType());
        dto.setSqlText(api.getSqlText());
        dto.setSqlParams(definition.getSqlParams());
        dto.setInvolvedTables(api.getInvolvedTables());
        dto.setOrderBy(api.getOrderBy());
        dto.setPaginated(api.getPaginated());
        dto.setPageSizeMax(api.getPageSizeMax());
        dto.setStatus(api.getStatus());
        dto.setDoc(buildDoc(api, definition));
        dto.setBoundKeys(listBoundKeys(id));
        dto.setCalls7d(countMap(callLogMapper.countCallsByApiIdsSince(
                List.of(id), LocalDateTime.now().minusDays(7))).getOrDefault(id, 0L));
        Map<Long, String> usernames = SystemUserResolver.usernames(systemUserApi,
                java.util.stream.Stream.of(api.getCreatedBy(), api.getUpdatedBy())
                        .filter(java.util.Objects::nonNull).distinct().toList());
        dto.setCreatedBy(api.getCreatedBy());
        dto.setCreatedByName(usernames.get(api.getCreatedBy()));
        dto.setUpdatedByName(usernames.get(api.getUpdatedBy()));
        dto.setCreatedAt(api.getCreatedAt());
        dto.setUpdatedAt(api.getUpdatedAt());
        return dto;
    }

    // ---------- 内部方法 ----------

    /** 查询定义形态白名单（TABLE_SELECT 选表 / CUSTOM_SQL 自定义 SQL） */
    private String normalizeQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return DataApi.QUERY_TYPE_TABLE_SELECT;
        }
        String t = queryType.trim().toUpperCase();
        if (!DataApi.QUERY_TYPE_TABLE_SELECT.equals(t) && !DataApi.QUERY_TYPE_CUSTOM_SQL.equals(t)) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID,
                    "查询定义形态仅支持 TABLE_SELECT / CUSTOM_SQL: " + queryType);
        }
        return t;
    }

    /** 选表形态定义落库：库/表必填校验 + filters/fields 白名单 + 单表数据权限/敏感度闸门（一期流程不变） */
    private void applyTableSelectDefinition(DataApi api, DataApiCreateRequest request) {
        String database = request.getDatabaseName();
        String table = request.getTableName();
        if (database == null || database.isBlank()) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "选表形态必须指定库名");
        }
        if (table == null || table.isBlank()) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "选表形态必须指定表名");
        }
        api.setDatabaseName(database.trim());
        api.setSchemaName(trimToNull(request.getSchemaName()));
        api.setTableName(table.trim());
        api.setMetadataTableId(request.getMetadataTableId());
        api.setParamsJson(JSON.toJSONString(buildDefinition(request.getFilters(), request.getFields())));
        api.setOrderBy(normalizeOrderBy(request.getOrderBy()));
        checkDataPermission(api.getDatasourceId(), api.getDatabaseName(), api.getTableName());
        checkSensitivityGate(api.getDatasourceId(), api.getDatabaseName(), api.getSchemaName(), api.getTableName());
    }

    /**
     * CUSTOM_SQL 创建定义（技术文档 §3.1 流程）：只读校验 + 涉及表解析 → 参数校验（9018）→
     * 逐表 fail-closed 闸门（9019）→ 落库 sql_text/involved_tables/params_json。
     *
     * @return 涉及表清单（供血缘写入）
     */
    private List<CustomSqlService.InvolvedTable> applyCustomSqlDefinition(DataApi api, DataApiCreateRequest request) {
        String sql = trimToNull(request.getSqlText());
        if (sql == null) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "CUSTOM_SQL 形态必须提供 SQL 文本");
        }
        String databaseName = trimToNull(request.getDatabaseName());
        if (databaseName == null) {
            databaseName = defaultDatabase(api.getDatasourceId());
        }
        String schemaName = trimToNull(request.getSchemaName());
        api.setDatabaseName(databaseName);
        api.setSchemaName(schemaName);
        api.setTableName(trimToNull(request.getTableName()) == null ? "" : request.getTableName().trim());
        api.setMetadataTableId(null);
        api.setOrderBy(null);

        List<CustomSqlService.InvolvedTable> tables =
                customSqlService.extractInvolvedTables(sql, databaseName, schemaName);
        if (tables.isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOM_SQL_INVALID, "SQL 未引用任何表，无法校验权限与血缘");
        }
        customSqlService.validateParamDefs(sql, request.getSqlParams());
        for (CustomSqlService.InvolvedTable t : tables) {
            checkCustomSqlTableGates(api.getDatasourceId(), t.database(), t.schema(), t.table());
        }
        api.setSqlText(sql);
        api.setInvolvedTables(involvedTablesJson(api.getDatasourceId(), tables));
        api.setParamsJson(JSON.toJSONString(customSqlDefinition(request.getSqlParams())));
        return tables;
    }

    /** CUSTOM_SQL 编辑定义：换 SQL 后重新校验 + 重新过闸门（数据源/默认库沿用已存记录） */
    private List<CustomSqlService.InvolvedTable> applyCustomSqlUpdate(DataApi api, DataApiUpdateRequest request,
                                                                      UpdateWrapper<DataApi> wrapper) {
        String sql = trimToNull(request.getSqlText());
        if (sql == null) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "CUSTOM_SQL 形态必须提供 SQL 文本");
        }
        List<CustomSqlService.InvolvedTable> tables = customSqlService.extractInvolvedTables(
                sql, api.getDatabaseName(), api.getSchemaName());
        if (tables.isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOM_SQL_INVALID, "SQL 未引用任何表，无法校验权限与血缘");
        }
        customSqlService.validateParamDefs(sql, request.getSqlParams());
        for (CustomSqlService.InvolvedTable t : tables) {
            checkCustomSqlTableGates(api.getDatasourceId(), t.database(), t.schema(), t.table());
        }
        wrapper.set("sql_text", sql);
        wrapper.set("involved_tables", involvedTablesJson(api.getDatasourceId(), tables));
        wrapper.set("params_json", JSON.toJSONString(customSqlDefinition(request.getSqlParams())));
        wrapper.set("order_by", null);
        return tables;
    }

    /** CUSTOM_SQL 的 params_json 定义（queryType + sqlParams，不含 filters/fields） */
    private DataApiDefinition customSqlDefinition(List<CustomSqlParamDef> sqlParams) {
        DataApiDefinition definition = new DataApiDefinition();
        definition.setQueryType(DataApi.QUERY_TYPE_CUSTOM_SQL);
        definition.setSqlParams(sqlParams);
        return definition;
    }

    /**
     * CUSTOM_SQL 逐表 fail-closed 闸门（S13-ADR-003）：任一涉及表机密/未特批内部/无数据权限 → 9019 整体拒绝；
     * 分级服务不可达保持 9012（fail-closed），不折算成 9019。
     */
    private void checkCustomSqlTableGates(Long datasourceId, String database, String schema, String table) {
        try {
            checkDataPermission(datasourceId, database, table);
            checkSensitivityGate(datasourceId, database, schema, table);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SENSITIVITY_SERVICE_UNAVAILABLE) {
                throw e;
            }
            throw new BusinessException(ErrorCode.CUSTOM_SQL_TABLE_FORBIDDEN,
                    "涉及表被安全闸门拒绝: " + qualifiedTable(database, schema, table) + "（" + e.getMessage() + "）");
        }
    }

    private String qualifiedTable(String database, String schema, String table) {
        if (schema != null && !schema.isBlank()) {
            return schema + "." + table;
        }
        if (database != null && !database.isBlank()) {
            return database + "." + table;
        }
        return table;
    }

    /** 发布/下线前对 CUSTOM_SQL 按落库的涉及表清单逐表重新过闸门（fail-closed；清单缺失/损坏则整体拒绝） */
    private void recheckCustomSqlGates(DataApi api) {
        List<CustomSqlService.InvolvedTable> tables = parseInvolvedTables(api.getInvolvedTables());
        if (tables.isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOM_SQL_TABLE_FORBIDDEN,
                    "涉及表清单缺失或损坏，无法通过安全闸门，已阻止操作");
        }
        for (CustomSqlService.InvolvedTable t : tables) {
            checkCustomSqlTableGates(api.getDatasourceId(), t.database(), t.schema(), t.table());
        }
    }

    /** 解析 involved_tables JSON（[{datasourceId,database,schema,table}]）为涉及表清单 */
    private List<CustomSqlService.InvolvedTable> parseInvolvedTables(String involvedTablesJson) {
        if (involvedTablesJson == null || involvedTablesJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = JSON.parseObject(involvedTablesJson,
                    new com.alibaba.fastjson2.TypeReference<List<Map<String, Object>>>() {
                    });
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            List<CustomSqlService.InvolvedTable> tables = new ArrayList<>(list.size());
            for (Map<String, Object> m : list) {
                String table = m.get("table") == null ? null : m.get("table").toString();
                if (table == null || table.isBlank()) {
                    continue;
                }
                tables.add(new CustomSqlService.InvolvedTable(
                        m.get("database") == null ? null : m.get("database").toString(),
                        m.get("schema") == null ? null : m.get("schema").toString(),
                        table.trim()));
            }
            return tables;
        } catch (Exception e) {
            logger.warn("涉及表清单 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 涉及表清单 JSON（[{datasourceId,database,schema,table}]，技术文档 §2.1，冗余存储避免每次重解析） */
    private String involvedTablesJson(Long datasourceId, List<CustomSqlService.InvolvedTable> tables) {
        List<Map<String, Object>> list = new ArrayList<>(tables.size());
        for (CustomSqlService.InvolvedTable t : tables) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("datasourceId", datasourceId);
            m.put("database", t.database());
            m.put("schema", t.schema());
            m.put("table", t.table());
            list.add(m);
        }
        return JSON.toJSONString(list);
    }

    /**
     * CUSTOM_SQL 表级血缘写入（技术文档 §3.4）：source/target 均取涉及表限定名（表级，列空），
     * dagId 空、dagName=API 名、lineageType=SQL；血缘写入失败降级 warn 不阻断保存（RemoteCalls 兜底）。
     */
    private void writeLineage(DataApi api, List<CustomSqlService.InvolvedTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        LineageRecordBatchRequest request = new LineageRecordBatchRequest();
        request.setRecords(tables.stream().map(t -> {
            LineageRecordItemDTO item = new LineageRecordItemDTO();
            String qualified = t.qualified();
            item.setSourceTable(qualified);
            item.setTargetTable(qualified);
            item.setLineageType("SQL");
            item.setDagId(null);
            item.setDagName(api.getName());
            return item;
        }).toList());
        RemoteCalls.execute("governance.lineage.records-batch", () -> {
            Result<Integer> result = metadataWriteApi.saveLineageRecords(request);
            if (result == null || result.data() == null || result.data() < request.getRecords().size()) {
                logger.warn("血缘记录批量写入条数不符（降级）: apiId={}, expected={}, actual={}",
                        api.getId(), request.getRecords().size(), result == null ? null : result.data());
            }
        });
    }

    /** CUSTOM_SQL 未传库名时的默认库：内置 Doris 用当前连接库，外部数据源用其配置库 */
    private String defaultDatabase(Long datasourceId) {
        if (datasourceId == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
            return DorisDataSourceConfig.currentDatabase();
        }
        DataSourceInfo ds = validateDatasource(datasourceId);
        return ds == null || ds.getDatabaseName() == null ? "" : ds.getDatabaseName();
    }

    /**
     * API 管理列表页统计卡：按状态聚合计数（未删除行）+ 近 7 天总调用（含已软删 API 的历史调用）。
     */
    public DataApiSummaryDTO summary() {
        DataApiSummaryDTO dto = new DataApiSummaryDTO();
        dto.setPublishedCount(countByStatus(DataApi.STATUS_PUBLISHED));
        dto.setCreatedCount(countByStatus(DataApi.STATUS_CREATED));
        dto.setDisabledCount(countByStatus(DataApi.STATUS_DISABLED));
        Long total = callLogMapper.countCallsSince(LocalDateTime.now().minusDays(7));
        dto.setTotalCalls7d(total == null ? 0L : total);
        return dto;
    }

    private Long countByStatus(String status) {
        return dataApiMapper.selectCount(new QueryWrapper<DataApi>()
                .eq("deleted", 0).eq("status", status));
    }

    /**
     * 批量反查源表敏感度（列表/详情展示用，读路径 fail-open）：按 数据源+库+schema 分组调用
     * governance 批量敏感度端点；governance 不可达时降级为空 Map（前端显示「未知」），不阻断列表。
     */
    private Map<String, String> loadSensitivityLevels(List<DataApi> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        Map<String, List<DataApi>> groups = records.stream()
                // CUSTOM_SQL 形态无单表绑定（table_name 为空串），不参与单表敏感度展示
                .filter(api -> api.getTableName() != null && !api.getTableName().isBlank())
                .collect(Collectors.groupingBy(
                api -> sensitivityKey(api.getDatasourceId(), api.getDatabaseName(), api.getSchemaName(), ""),
                LinkedHashMap::new, Collectors.toList()));
        Map<String, String> result = new LinkedHashMap<>();
        for (List<DataApi> group : groups.values()) {
            DataApi first = group.get(0);
            String tables = group.stream().map(DataApi::getTableName).distinct().collect(Collectors.joining(","));
            try {
                Result<List<MetadataTableSensitivityDTO>> resp = governanceMetadataApi.getSensitivity(
                        first.getDatasourceId(), trimToNull(first.getDatabaseName()),
                        trimToNull(first.getSchemaName()), tables);
                if (resp == null || resp.code() != 200 || resp.data() == null) {
                    continue;
                }
                for (MetadataTableSensitivityDTO dto : resp.data()) {
                    result.put(sensitivityKey(dto.getDatasourceId(), dto.getDatabaseName(),
                            dto.getSchemaName(), dto.getTableName()), dto.getSensitivityLevel());
                }
            } catch (Exception e) {
                logger.warn("批量反查表敏感度失败，降级为未知: datasourceId={}, tables={}, err={}",
                        first.getDatasourceId(), tables, e.getMessage());
            }
        }
        return result;
    }

    private String sensitivityKey(Long datasourceId, String database, String schema, String table) {
        return datasourceId + "|" + (database == null ? "" : database) + "|"
                + (schema == null ? "" : schema) + "|" + (table == null ? "" : table);
    }

    /** 加载未删除 API，查无抛 9008 */
    private DataApi loadApi(Long id) {
        DataApi api = dataApiMapper.selectOne(
                new QueryWrapper<DataApi>().eq("id", id).eq("deleted", 0));
        if (api == null) {
            throw new BusinessException(ErrorCode.API_NOT_FOUND);
        }
        return api;
    }

    /**
     * 归一对外路径：接受「orders」「/orders」「/open-api/v1/orders」三种输入，统一为完整形态。
     */
    private String normalizePath(String raw) {
        String p = raw.trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.startsWith("open-api/v1/")) {
            p = p.substring("open-api/v1/".length());
        }
        if (!PATH_SEGMENT_PATTERN.matcher(p).matches()) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID,
                    "API 路径非法：仅支持小写字母/数字开头，可含 - _，如 /open-api/v1/orders");
        }
        return PATH_PREFIX + p;
    }

    /** 路径查重（未删除行唯一；excludeId 用于编辑时排除自身） */
    private void assertPathAvailable(String path, Long excludeId) {
        QueryWrapper<DataApi> wrapper = new QueryWrapper<DataApi>().eq("path", path).eq("deleted", 0);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (dataApiMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.API_PATH_EXISTS, "API 路径已存在: " + path);
        }
    }

    /** 组装并校验 API 定义（filters 类型/标识符白名单 + fields 标识符白名单，去重保序） */
    private DataApiDefinition buildDefinition(List<ApiParamDef> filters, List<String> fields) {
        DataApiDefinition definition = new DataApiDefinition();
        if (filters != null && !filters.isEmpty()) {
            if (filters.size() > MAX_FILTERS) {
                throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "参数化筛选最多 " + MAX_FILTERS + " 个");
            }
            Map<String, ApiParamDef> dedup = new LinkedHashMap<>();
            for (ApiParamDef filter : filters) {
                String field = filter.getField() == null ? "" : filter.getField().trim();
                if (!IDENTIFIER_PATTERN.matcher(field).matches()) {
                    throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "筛选字段名非法: " + field);
                }
                String type = filter.getType() == null ? "" : filter.getType().trim().toUpperCase();
                if (!ApiParamDef.TYPE_EQ.equals(type) && !ApiParamDef.TYPE_RANGE.equals(type)) {
                    throw new BusinessException(ErrorCode.API_DEFINITION_INVALID,
                            "筛选类型仅支持 EQ（等值）/ RANGE（范围）: " + filter.getType());
                }
                ApiParamDef def = new ApiParamDef();
                def.setField(field);
                def.setType(type);
                dedup.put(field + ":" + type, def);
            }
            definition.setFilters(new ArrayList<>(dedup.values()));
        }
        if (fields != null && !fields.isEmpty()) {
            if (fields.size() > MAX_FIELDS) {
                throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "返回字段最多 " + MAX_FIELDS + " 个");
            }
            Map<String, String> dedup = new LinkedHashMap<>();
            for (String field : fields) {
                String f = field == null ? "" : field.trim();
                if (!IDENTIFIER_PATTERN.matcher(f).matches()) {
                    throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "返回字段名非法: " + f);
                }
                dedup.put(f, f);
            }
            definition.setFields(new ArrayList<>(dedup.keySet()));
        }
        return definition;
    }

    /** 排序白名单校验（order_by 会拼进 SQL，仅允许 列名 + ASC/DESC） */
    private String normalizeOrderBy(String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            return null;
        }
        String ob = orderBy.trim();
        if (!ORDER_BY_PATTERN.matcher(ob).matches()) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID,
                    "排序仅支持「列名 ASC|DESC」，如 cnt DESC");
        }
        return ob;
    }

    private Integer normalizePageSizeMax(Integer pageSizeMax) {
        if (pageSizeMax == null) {
            return 100;
        }
        if (pageSizeMax < 1 || pageSizeMax > 1000) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "pageSize 上限需在 1~1000 之间");
        }
        return pageSizeMax;
    }

    /**
     * 数据权限白名单校验（fail-closed，Sprint 11 F2）。
     * <p>
     * 内置 Doris（-1）全量放行；外部数据源按白名单最细粒度匹配；
     * 权限服务不可用拒绝（安全默认，防止绕过前端直接提交 API 定义）。
     */
    private void checkDataPermission(Long datasourceId, String database, String table) {
        if (datasourceId != null && datasourceId == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
            return;
        }
        Long userId;
        try {
            userId = StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return; // 内部场景无登录态不校验
        }
        if (userId == null) {
            return;
        }
        UserDataPermissionDTO perm = DataPermissionResolver.resolveFailClosed(systemPermissionApi, userId);
        if (!DataPermissionMatcher.canAccessTable(perm, datasourceId, database, table)) {
            throw new BusinessException(ErrorCode.DATA_PERMISSION_DENIED, "无权限访问数据资源: " + table);
        }
    }

    /** 数据源存在性校验（内置 Doris=-1 直接放行返回 null；外部经 engineering 查，查无抛 3001，返回连接信息） */
    private DataSourceInfo validateDatasource(Long datasourceId) {
        if (datasourceId == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
            return null;
        }
        Result<DataSourceInfo> resp = RemoteCalls.execute("engineering.datasource.getById",
                () -> datasourceApi.getById(datasourceId), null);
        if (resp == null || resp.data() == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        return resp.data();
    }

    /**
     * 表敏感度闸门（fail-closed，技术文档 §4.4）：
     * governance 不可达 → 9012 拒绝；命中 CONFIDENTIAL → 9004 禁止；INTERNAL 未开白 → 9004 需开白；
     * 未打标（空）按默认 PUBLIC 放行。多行命中取最严（保守）。
     */
    private void checkSensitivityGate(Long datasourceId, String database, String schema, String table) {
        Result<List<MetadataTableSensitivityDTO>> resp = governanceMetadataApi.getSensitivity(
                datasourceId, trimToNull(database), trimToNull(schema), table);
        if (resp == null || resp.code() != 200) {
            throw new BusinessException(ErrorCode.SENSITIVITY_SERVICE_UNAVAILABLE,
                    "分级服务暂不可用，已阻止本次操作，请稍后重试");
        }
        List<MetadataTableSensitivityDTO> list = resp.data();
        if (list == null || list.isEmpty()) {
            return; // 未打标默认 PUBLIC
        }
        boolean confidential = list.stream().anyMatch(dto -> CONFIDENTIAL.equals(dto.getSensitivityLevel()));
        if (confidential) {
            throw new BusinessException(ErrorCode.TABLE_SENSITIVE,
                    "表敏感度为机密，禁止对外提供 API: " + table);
        }
        boolean internalBlocked = list.stream().anyMatch(dto -> INTERNAL.equals(dto.getSensitivityLevel())
                && (dto.getApiExempted() == null || dto.getApiExempted() != 1));
        if (internalBlocked) {
            throw new BusinessException(ErrorCode.TABLE_SENSITIVE,
                    "表敏感度为内部，需超级管理员特批开放后才可生成对外 API: " + table);
        }
    }

    /** 自动文档（PRD 6.3：创建时生成 OpenAPI 描述，详情页查看 + 复制 curl 示例；Sprint 13 双形态） */
    private DataApiDocDTO buildDoc(DataApi api, DataApiDefinition definition) {
        DataApiDocDTO doc = new DataApiDocDTO();
        doc.setMethod(api.getMethod());
        doc.setPath(api.getPath());
        doc.setFullPath("/api/data-service" + api.getPath());
        doc.setAuth("请求头 X-API-Key: <你的API Key>（Key 需绑定本 API）");

        boolean customSql = DataApi.QUERY_TYPE_CUSTOM_SQL.equals(api.getQueryType());
        List<DataApiDocDTO.DocParam> params = new ArrayList<>();
        if (customSql) {
            if (definition.getSqlParams() != null) {
                for (CustomSqlParamDef p : definition.getSqlParams()) {
                    String required = p.getRequired() == null || p.getRequired() ? "必填" : "选填";
                    params.add(docParam(p.getName(),
                            "SQL 参数：" + p.getName() + "（类型 " + p.getType() + "，" + required + "）"));
                }
            }
        } else if (definition.getFilters() != null) {
            for (ApiParamDef filter : definition.getFilters()) {
                if (ApiParamDef.TYPE_RANGE.equals(filter.getType())) {
                    params.add(docParam("min_" + filter.getField(), "范围筛选：" + filter.getField() + " 下限"));
                    params.add(docParam("max_" + filter.getField(), "范围筛选：" + filter.getField() + " 上限"));
                } else {
                    params.add(docParam(filter.getField(), "等值筛选：" + filter.getField() + "=value"));
                }
            }
        }
        if (api.getPaginated() != null && api.getPaginated() == 1) {
            params.add(docParam("page", "页码，从 1 开始（默认 1）"));
            params.add(docParam("pageSize", "每页条数（默认 20，上限 " + api.getPageSizeMax() + "）"));
        }
        doc.setParams(params);
        doc.setResponse("{\"code\":200,\"message\":\"success\",\"data\":{\"records\":[{...}],\"total\":0}}");
        StringBuilder curl = new StringBuilder("curl -H 'X-API-Key: <你的API Key>' 'http://localhost:8080")
                .append(doc.getFullPath()).append("?");
        if (customSql && definition.getSqlParams() != null) {
            for (CustomSqlParamDef p : definition.getSqlParams()) {
                curl.append(p.getName()).append('=').append(sampleParamValue(p.getType())).append('&');
            }
        }
        if (api.getPaginated() != null && api.getPaginated() == 1) {
            curl.append("page=1&pageSize=20");
        }
        doc.setCurl(curl.append("'").toString());
        return doc;
    }

    /** curl 示例参数样例值（按类型） */
    private String sampleParamValue(String type) {
        String t = type == null ? "" : type.trim().toUpperCase();
        return switch (t) {
            case CustomSqlService.TYPE_LONG -> "1";
            case CustomSqlService.TYPE_DECIMAL -> "1.5";
            case CustomSqlService.TYPE_DATE -> "2024-01-01";
            case CustomSqlService.TYPE_DATETIME -> "2024-01-01T00:00:00";
            case CustomSqlService.TYPE_BOOLEAN -> "true";
            default -> "value";
        };
    }

    private DataApiDocDTO.DocParam docParam(String name, String description) {
        DataApiDocDTO.DocParam param = new DataApiDocDTO.DocParam();
        param.setName(name);
        param.setDescription(description);
        return param;
    }

    /** 绑定 Key 列表（API 详情展示） */
    private List<ApiKeyBriefDTO> listBoundKeys(Long apiId) {
        List<ApiKeyBinding> bindings = bindingMapper.selectList(
                new QueryWrapper<ApiKeyBinding>().eq("api_id", apiId));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, com.datanest.dataservice.entity.ApiKey> keyMap = apiKeyMapper.selectBatchIds(
                bindings.stream().map(ApiKeyBinding::getKeyId).toList()).stream()
                .collect(Collectors.toMap(com.datanest.dataservice.entity.ApiKey::getId, Function.identity()));
        return bindings.stream().map(binding -> {
            ApiKeyBriefDTO dto = new ApiKeyBriefDTO();
            dto.setId(binding.getKeyId());
            com.datanest.dataservice.entity.ApiKey key = keyMap.get(binding.getKeyId());
            if (key != null) {
                dto.setName(key.getName());
                dto.setStatus(key.getStatus());
            }
            return dto;
        }).toList();
    }

    /** 数据源显示名批量反查（内置 Doris=-1 → Doris 数仓；其余经 engineering 批量查，失败降级空） */
    private Map<Long, String> datasourceNames(List<Long> datasourceIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        List<Long> externalIds = datasourceIds.stream().distinct().filter(id -> {
            if (id == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
                names.put(id, DorisConstants.BUILTIN_DORIS_NAME);
                return false;
            }
            return true;
        }).toList();
        if (externalIds.isEmpty()) {
            return names;
        }
        IdsRequest request = new IdsRequest();
        request.setIds(externalIds);
        Result<Map<Long, DataSourceInfo>> resp = RemoteCalls.execute("engineering.datasource.batchGet",
                () -> datasourceApi.batchGet(request), null);
        if (resp != null && resp.data() != null) {
            resp.data().forEach((id, info) -> names.put(id, info.getName()));
        }
        return names;
    }

    private DataApiDefinition parseDefinition(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new DataApiDefinition();
        }
        try {
            DataApiDefinition definition = JSON.parseObject(paramsJson, DataApiDefinition.class);
            return definition == null ? new DataApiDefinition() : definition;
        } catch (Exception e) {
            logger.warn("API 定义 JSON 解析失败，按空定义处理: {}", e.getMessage());
            return new DataApiDefinition();
        }
    }

    private Map<Long, Long> countMap(List<RefCount> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        return counts.stream().collect(Collectors.toMap(RefCount::getRefId, RefCount::getCnt));
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
