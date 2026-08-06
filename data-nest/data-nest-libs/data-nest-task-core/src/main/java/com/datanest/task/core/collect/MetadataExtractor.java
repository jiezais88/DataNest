package com.datanest.task.core.collect;

import com.datanest.task.core.entity.DataSourceConnection;

import java.sql.SQLException;
import java.util.List;

public interface MetadataExtractor {

    List<String> extractSchemas(DataSourceConnection ds) throws SQLException;

    List<TableMetadata> extractTables(DataSourceConnection ds, String schema) throws SQLException;
}
