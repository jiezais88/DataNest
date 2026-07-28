package com.datanest.engineering.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcPreviewHelper;
import com.datanest.engineering.dto.DataPreviewResult;
import com.datanest.engineering.entity.DataSourceConnection;
import com.datanest.engineering.mapper.DataSourceMapper;
import org.springframework.stereotype.Service;

@Service
public class DataPreviewService {

    private final DataSourceMapper dataSourceMapper;
    private final EncryptionConfig encryptionConfig;

    public DataPreviewService(DataSourceMapper dataSourceMapper, EncryptionConfig encryptionConfig) {
        this.dataSourceMapper = dataSourceMapper;
        this.encryptionConfig = encryptionConfig;
    }

    public DataPreviewResult preview(Long datasourceId, String database, String schema, String tableName) {
        DataSourceConnection ds = dataSourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
        JdbcPreviewHelper.PreviewResult result = JdbcPreviewHelper.preview(
                ds.getType(), ds.getHost(), ds.getPort(),
                database != null ? database : ds.getDatabaseName(),
                schema,
                ds.getUsername(), password,
                tableName);
        return new DataPreviewResult(result.columns(), result.rows(), result.rowCount(), result.totalRowCount());
    }
}
