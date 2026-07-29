package com.datanest.task.core.collect;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TableMetadata {

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String tableComment;

    private List<ColumnMetadata> columns = new ArrayList<>();
}
