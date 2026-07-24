package com.datanest.governance.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcSchemaExtractor;
import com.datanest.governance.entity.DataSourceConnection;
import com.datanest.governance.mapper.DataSourceConnectionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchemaService {

    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final EncryptionConfig encryptionConfig;

    public SchemaService(DataSourceConnectionMapper dataSourceConnectionMapper,
                         EncryptionConfig encryptionConfig) {
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.encryptionConfig = encryptionConfig;
    }

    public List<String> listSchemas(Long datasourceId) {
        DataSourceConnection connection = dataSourceConnectionMapper.selectById(datasourceId);
        if (connection == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }

        String password = encryptionConfig.decrypt(connection.getPassword());
        return JdbcSchemaExtractor.extractSchemas(
                connection.getType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDatabaseName(),
                connection.getSchemaName(),
                connection.getUsername(),
                password
        );
    }
}
