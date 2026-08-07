package com.datanest.governance.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.common.constant.SourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcPreviewHelper;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.governance.dto.MetadataPreviewResult;
import com.datanest.task.core.entity.MetadataTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MetadataPreviewService {

    private final MetadataService metadataService;
    private final EngineeringDatasourceApi datasourceApi;
    private final EncryptionConfig encryptionConfig;

    private final String builtInDorisHost;
    private final int builtInDorisQueryPort;
    private final String builtInDorisUser;
    private final String builtInDorisPassword;

    public MetadataPreviewService(MetadataService metadataService,
                                  EngineeringDatasourceApi datasourceApi,
                                  EncryptionConfig encryptionConfig,
                                  @Value("${datanest.doris.fe-host:localhost}") String builtInDorisHost,
                                  @Value("${datanest.doris.fe-query-port:9030}") int builtInDorisQueryPort,
                                  @Value("${datanest.doris.user:root}") String builtInDorisUser,
                                  @Value("${datanest.doris.password:}") String builtInDorisPassword) {
        this.metadataService = metadataService;
        this.datasourceApi = datasourceApi;
        this.encryptionConfig = encryptionConfig;
        this.builtInDorisHost = builtInDorisHost;
        this.builtInDorisQueryPort = builtInDorisQueryPort;
        this.builtInDorisUser = builtInDorisUser;
        this.builtInDorisPassword = builtInDorisPassword;
    }

    public MetadataPreviewResult preview(Long tableId) {
        MetadataTable table = metadataService.resolveTable(tableId);
        if (table == null || !MetadataSourceStatus.ONLINE.getCode().equals(table.getSourceStatus())) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }

        JdbcPreviewHelper.PreviewResult result;
        if (table.getDatasourceId() == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        if (table.getDatasourceId() == -1L || SourceType.BUILTIN_DORIS.getCode().equals(table.getSourceType())) {
            result = JdbcPreviewHelper.preview(
                    DataSourceType.DORIS.getCode(), builtInDorisHost, builtInDorisQueryPort,
                    table.getDatabaseName(),
                    table.getSchemaName(),
                    builtInDorisUser, builtInDorisPassword,
                    table.getTableName());
        } else {
            // 经 engineering 服务 Feign 读连接，fail-fast：预览是同步用户操作，
            // 连接读不到（含熔断降级返回空）直接报错，不降级
            Result<DataSourceInfo> readResult = datasourceApi.getById(table.getDatasourceId());
            DataSourceInfo ds = readResult == null ? null : readResult.data();
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
