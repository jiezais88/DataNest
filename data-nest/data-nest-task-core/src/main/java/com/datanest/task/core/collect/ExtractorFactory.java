package com.datanest.task.core.collect;

import org.springframework.stereotype.Component;

@Component
public class ExtractorFactory {

    private final MysqlMetadataExtractor mysqlExtractor;
    private final PostgresMetadataExtractor postgresExtractor;

    public ExtractorFactory(MysqlMetadataExtractor mysqlExtractor, PostgresMetadataExtractor postgresExtractor) {
        this.mysqlExtractor = mysqlExtractor;
        this.postgresExtractor = postgresExtractor;
    }

    public MetadataExtractor getExtractor(String type) {
        return switch (type.toUpperCase()) {
            case "MYSQL", "DORIS" -> mysqlExtractor;
            case "POSTGRESQL", "POSTGRES" -> postgresExtractor;
            default -> throw new IllegalArgumentException("Unsupported data source type: " + type);
        };
    }
}
