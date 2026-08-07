package com.datanest.task.core.collect;

import com.datanest.engineering.api.dto.DataSourceInfo;

import java.sql.SQLException;
import java.util.List;

public interface MetadataExtractor {

    List<String> extractSchemas(DataSourceInfo ds) throws SQLException;

    List<TableMetadata> extractTables(DataSourceInfo ds, String schema) throws SQLException;
}
