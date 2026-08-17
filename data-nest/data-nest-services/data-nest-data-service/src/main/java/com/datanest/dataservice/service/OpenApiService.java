package com.datanest.dataservice.service;

import com.alibaba.fastjson2.JSON;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DataSourceStatus;
import com.datanest.common.constant.DorisConstants;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.dataservice.dto.DataApiDefinition;
import com.datanest.dataservice.dto.OpenApiResult;
import com.datanest.dataservice.entity.ApiKey;
import com.datanest.dataservice.entity.DataApi;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 对外数据 API 执行服务（Sprint 10 F3）。
 * <p>
 * 流程（对齐技术文档 §4.2）：状态校验（未发布 404）→ 熔断检查（数据源维度 503）→ 构造参数化 SQL
 * （OpenApiSqlBuilder，filters 白名单绑定 + 分页 + orderBy）→ 执行（内置 Doris / 外部数据源）→
 * 分页时 COUNT 总数 → 记录熔断结果 + 异步写 api_call_log。
 */
@Service
@RefreshScope
public class OpenApiService {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiService.class);

    private final OpenApiSqlBuilder sqlBuilder;
    private final CustomSqlService customSqlService;
    private final CancelableSqlExecutor executor;
    private final EngineeringDatasourceApi datasourceApi;
    private final EncryptionConfig encryptionConfig;
    private final CircuitBreakerService circuitBreakerService;
    private final ApiCallLogWriter callLogWriter;

    @Value("${datanest.dataservice.sql.query-timeout-seconds:60}")
    private int queryTimeoutSeconds;

    public OpenApiService(OpenApiSqlBuilder sqlBuilder,
                          CustomSqlService customSqlService,
                          CancelableSqlExecutor executor,
                          EngineeringDatasourceApi datasourceApi,
                          EncryptionConfig encryptionConfig,
                          CircuitBreakerService circuitBreakerService,
                          ApiCallLogWriter callLogWriter) {
        this.sqlBuilder = sqlBuilder;
        this.customSqlService = customSqlService;
        this.executor = executor;
        this.datasourceApi = datasourceApi;
        this.encryptionConfig = encryptionConfig;
        this.circuitBreakerService = circuitBreakerService;
        this.callLogWriter = callLogWriter;
    }

    /**
     * 执行对外 API（api/key 已由 OpenApiKeyFilter 认证并绑定）。
     */
    public OpenApiResult execute(DataApi api, ApiKey key, Map<String, String> queryParams) {
        long start = System.currentTimeMillis();
        Long datasourceId = api.getDatasourceId();

        // 1. 状态校验：仅 PUBLISHED 可调用（CREATED/DISABLED → 404，不涉熔断）
        if (!DataApi.STATUS_PUBLISHED.equals(api.getStatus())) {
            callLogWriter.write(api.getId(), key.getId(), key.getName(), 404, elapsed(start));
            throw new BusinessException(ErrorCode.API_NOT_PUBLISHED, "API 未发布或已下线");
        }
        // 2. 熔断检查：数据源维度开闸 → 503
        if (!circuitBreakerService.tryAcquire(datasourceId)) {
            callLogWriter.write(api.getId(), key.getId(), key.getName(), 503, elapsed(start));
            throw new BusinessException(ErrorCode.API_CIRCUIT_OPEN);
        }

        try {
            DataApiDefinition definition = parseDefinition(api.getParamsJson());
            DataSourceInfo ds = resolveDatasource(datasourceId); // 内置 Doris 返回 null
            String type = ds == null ? "DORIS" : ds.getType();

            int[] paging = parsePaging(api, queryParams);
            CancelableSqlExecutor.QueryResult qr;
            long total;
            if (DataApi.QUERY_TYPE_CUSTOM_SQL.equals(api.getQueryType())) {
                // 自定义 SQL（Sprint 13，技术文档 §3.2）：不支持外部 orderBy（PRD D9），
                // 仅绑定 SQL 参数 + 分页参数；执行前词法级 :param→? 并再次校验参数定义（fail-closed 兜底）
                CustomSqlService.BuiltSql built = customSqlService.buildQuery(
                        api.getSqlText(), definition.getSqlParams(), queryParams);
                if (api.getPaginated() != null && api.getPaginated() == 1) {
                    qr = execute(ds, customSqlService.wrapPagination(built.sql(), type, paging[0], paging[1]),
                            built.params());
                    total = qr.rows().size();
                    try {
                        total = countCustom(ds, customSqlService.wrapCount(built.sql()), built.params());
                    } catch (Exception e) {
                        logger.warn("COUNT 查询失败，降级为当前页行数: apiId={}, err={}", api.getId(), e.getMessage());
                    }
                } else {
                    qr = execute(ds, built.sql(), built.params());
                    total = qr.rows().size();
                }
            } else {
                OpenApiSqlBuilder.BuiltSql built = sqlBuilder.build(api, definition, type, queryParams, paging[0], paging[1]);
                qr = execute(ds, built.sql(), built.params());
                // 分页启用时 COUNT 总数；COUNT 失败降级为当前页行数（不阻断数据返回）
                total = qr.rows().size();
                if (api.getPaginated() != null && api.getPaginated() == 1) {
                    try {
                        total = count(api, definition, type, queryParams, ds);
                    } catch (Exception e) {
                        logger.warn("COUNT 查询失败，降级为当前页行数: apiId={}, err={}", api.getId(), e.getMessage());
                    }
                }
            }

            circuitBreakerService.recordSuccess(datasourceId, elapsed(start));
            callLogWriter.write(api.getId(), key.getId(), key.getName(), 200, elapsed(start));
            OpenApiResult result = new OpenApiResult();
            result.setRecords(qr.rows());
            result.setTotal(total);
            return result;
        } catch (BusinessException e) {
            circuitBreakerService.recordFailure(datasourceId, elapsed(start), e);
            callLogWriter.write(api.getId(), key.getId(), key.getName(), 500, elapsed(start));
            throw e;
        }
    }

    /** 数据源路由执行（内置 Doris 走 queryDoris，外部走 queryExternal，PreparedStatement 绑定参数） */
    private CancelableSqlExecutor.QueryResult execute(DataSourceInfo ds, String sql, List<Object> params) {
        if (ds == null) {
            return executor.queryDoris(sql, params, queryTimeoutSeconds);
        }
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
        return executor.queryExternal(ds.getType(), ds.getHost(), ds.getPort(), ds.getDatabaseName(),
                ds.getSchemaName(), ds.getUsername(), password, sql, params, queryTimeoutSeconds);
    }

    /** COUNT 总数查询（分页 total 用，选表形态） */
    private long count(DataApi api, DataApiDefinition definition, String type,
                       Map<String, String> queryParams, DataSourceInfo ds) {
        OpenApiSqlBuilder.BuiltSql built = sqlBuilder.buildCount(api, definition, type, queryParams);
        return firstRowLong(execute(ds, built.sql(), built.params()));
    }

    /** COUNT 总数查询（自定义 SQL 形态，外层 SELECT COUNT(*) FROM (sql) AS _c） */
    private long countCustom(DataSourceInfo ds, String sql, List<Object> params) {
        return firstRowLong(execute(ds, sql, params));
    }

    /** 取结果首行首列数值（COUNT 值） */
    private long firstRowLong(CancelableSqlExecutor.QueryResult qr) {
        if (qr.rows().isEmpty()) {
            return 0;
        }
        Object value = qr.rows().get(0).values().iterator().next();
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 数据源解析：内置 Doris（-1）返回 null；外部经 engineering 查连接信息，OFFLINE 拒绝 */
    private DataSourceInfo resolveDatasource(Long datasourceId) {
        if (datasourceId == DorisConstants.BUILTIN_DORIS_DATASOURCE_ID) {
            return null;
        }
        var resp = datasourceApi.getById(datasourceId);
        if (resp == null || resp.data() == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        DataSourceInfo ds = resp.data();
        if (DataSourceStatus.OFFLINE.getCode().equals(ds.getStatus())) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "数据源已下线");
        }
        return ds;
    }

    /** 分页参数解析：page 默认 1，pageSize 默认 20，clamp 到 [1, pageSizeMax] */
    private int[] parsePaging(DataApi api, Map<String, String> queryParams) {
        int page = parsePositiveInt(queryParams.get("page"), 1);
        int pageSize = parsePositiveInt(queryParams.get("pageSize"), 20);
        int maxSize = api.getPageSizeMax() == null ? 100 : api.getPageSizeMax();
        pageSize = Math.min(Math.max(pageSize, 1), maxSize);
        return new int[]{page, pageSize};
    }

    private int parsePositiveInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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

    private int elapsed(long start) {
        return (int) (System.currentTimeMillis() - start);
    }
}
