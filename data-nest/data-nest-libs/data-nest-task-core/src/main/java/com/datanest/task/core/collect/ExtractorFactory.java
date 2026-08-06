package com.datanest.task.core.collect;

import com.datanest.common.constant.DataSourceType;
import org.springframework.stereotype.Component;

@Component
public class ExtractorFactory {

    private final MysqlMetadataExtractor mysqlExtractor;
    private final PostgresMetadataExtractor postgresExtractor;
    private final OracleMetadataExtractor oracleExtractor;
    private final SqlServerMetadataExtractor sqlServerExtractor;

    public ExtractorFactory(MysqlMetadataExtractor mysqlExtractor,
                            PostgresMetadataExtractor postgresExtractor,
                            OracleMetadataExtractor oracleExtractor,
                            SqlServerMetadataExtractor sqlServerExtractor) {
        this.mysqlExtractor = mysqlExtractor;
        this.postgresExtractor = postgresExtractor;
        this.oracleExtractor = oracleExtractor;
        this.sqlServerExtractor = sqlServerExtractor;
    }

    public MetadataExtractor getExtractor(String type) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case MYSQL, DORIS -> mysqlExtractor;
            case POSTGRESQL -> postgresExtractor;
            case ORACLE -> oracleExtractor;
            case SQLSERVER -> sqlServerExtractor;
        };
    }
}
