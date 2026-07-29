package com.datanest.governance.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcPreviewHelper;
import com.datanest.governance.dto.MetadataPreviewResult;
import com.datanest.governance.entity.DataSourceConnection;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.mapper.DataSourceConnectionMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MetadataPreviewService {

    private final MetadataTableMapper metadataTableMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final EncryptionConfig encryptionConfig;

    private final String builtInDorisHost;
    private final int builtInDorisQueryPort;
    private final String builtInDorisUser;
    private final String builtInDorisPassword;

    public MetadataPreviewService(MetadataTableMapper metadataTableMapper,
                                  DataSourceConnectionMapper dataSourceConnectionMapper,
                                  EncryptionConfig encryptionConfig,
                                  @Value("${datanest.doris.fe-host:localhost}") String builtInDorisHost,
                                  @Value("${datanest.doris.fe-query-port:9030}") int builtInDorisQueryPort,
                                  @Value("${datanest.doris.user:root}") String builtInDorisUser,
                                  @Value("${datanest.doris.password:}") String builtInDorisPassword) {
        this.metadataTableMapper = metadataTableMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.encryptionConfig = encryptionConfig;
        this.builtInDorisHost = builtInDorisHost;
        this.builtInDorisQueryPort = builtInDorisQueryPort;
        this.builtInDorisUser = builtInDorisUser;
        this.builtInDorisPassword = builtInDorisPassword;
    }

    public MetadataPreviewResult preview(Long tableId) {
        MetadataTable table = metadataTableMapper.selectById(tableId);
        if (table == null || !"ONLINE".equals(table.getSourceStatus())) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }

        JdbcPreviewHelper.PreviewResult result;
        if (table.getDatasourceId() == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        if (table.getDatasourceId() == -1L || "BUILTIN_DORIS".equals(table.getSourceType())) {
            result = JdbcPreviewHelper.preview(
                    "DORIS", builtInDorisHost, builtInDorisQueryPort,
                    table.getDatabaseName(),
                    table.getSchemaName(),
                    builtInDorisUser, builtInDorisPassword,
                    table.getTableName());
        } else {
            DataSourceConnection ds = dataSourceConnectionMapper.selectById(table.getDatasourceId());
            if (ds == null) {
                throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
            }
            String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
            result = JdbcPreviewHelper.preview(
                    ds.getType(), ds.getHost(), ds.getPort(),
                    table.getDatabaseName(),
                    table.getSchemaName(),
                    ds.getUsername(), password,
                    table.getTableName());
        }
        return new MetadataPreviewResult(result.columns(), result.rows(), result.rowCount(), result.totalRowCount());
    }
}
