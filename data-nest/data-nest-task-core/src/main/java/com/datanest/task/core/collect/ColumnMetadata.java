package com.datanest.task.core.collect;

import lombok.Data;

@Data
public class ColumnMetadata {

    private String columnName;

    private String dataType;

    private String columnComment;

    private Boolean nullable;

    private String columnDefault;

    private Integer ordinalPosition;
}
