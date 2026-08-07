package com.datanest.task.core.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.common.util.JdbcPreviewHelper;
import com.datanest.common.util.JdbcSchemaExtractor;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.task.core.dto.DataPreviewResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源表预览服务。
 * Sprint 4 下沉到 task-core，供 engineering / governance 共用。
 * 微服务化 3.4：连接信息经 {@link EngineeringDatasourceApi} Feign 读取（fail-fast，
 * 预览是同步用户操作，连接读不到直接报错，不降级）。
 */
@Service
public class DataPreviewService {

    private final EngineeringDatasourceApi datasourceApi;
    private final EncryptionConfig encryptionConfig;

    public DataPreviewService(EngineeringDatasourceApi datasourceApi, EncryptionConfig encryptionConfig) {
        this.datasourceApi = datasourceApi;
        this.encryptionConfig = encryptionConfig;
    }

    public DataPreviewResult preview(Long datasourceId, String database, String schema, String tableName) {
        Result<DataSourceInfo> readResult = datasourceApi.getById(datasourceId);
        DataSourceInfo ds = readResult == null ? null : readResult.data();
        if (ds == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
        String effectiveDatabase = database != null ? database : ds.getDatabaseName();
        JdbcPreviewHelper.PreviewResult result = JdbcPreviewHelper.preview(
                ds.getType(), ds.getHost(), ds.getPort(),
                effectiveDatabase,
                schema,
                ds.getUsername(), password,
                tableName);
        Map<String, String> columnTypes = extractColumnTypes(ds, effectiveDatabase, schema, tableName, password);
        return new DataPreviewResult(result.columns(), columnTypes, result.rows(), result.rowCount(), result.totalRowCount());
    }

    private Map<String, String> extractColumnTypes(DataSourceInfo ds, String database, String schema,
                                                   String tableName, String password) {
        try {
            List<JdbcSchemaExtractor.ColumnInfo> columns = JdbcSchemaExtractor.extractColumns(
                    ds.getType(), ds.getHost(), ds.getPort(),
                    database, schema,
                    ds.getUsername(), password,
                    tableName);
            Map<String, String> types = new LinkedHashMap<>();
            for (JdbcSchemaExtractor.ColumnInfo column : columns) {
                types.put(column.columnName(), column.dataType());
            }
            return types;
        } catch (Exception e) {
            return null;
        }
    }
}
