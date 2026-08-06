package com.datanest.task.core.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcPreviewHelper;
import com.datanest.common.util.JdbcSchemaExtractor;
import com.datanest.task.core.dto.DataPreviewResult;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源表预览服务。
 * Sprint 4 下沉到 task-core，供 engineering / governance 共用。
 */
@Service
public class DataPreviewService {

    private final DataSourceConnectionMapper dataSourceMapper;
    private final EncryptionConfig encryptionConfig;

    public DataPreviewService(DataSourceConnectionMapper dataSourceMapper, EncryptionConfig encryptionConfig) {
        this.dataSourceMapper = dataSourceMapper;
        this.encryptionConfig = encryptionConfig;
    }

    public DataPreviewResult preview(Long datasourceId, String database, String schema, String tableName) {
        DataSourceConnection ds = dataSourceMapper.selectById(datasourceId);
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

    private Map<String, String> extractColumnTypes(DataSourceConnection ds, String database, String schema,
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
