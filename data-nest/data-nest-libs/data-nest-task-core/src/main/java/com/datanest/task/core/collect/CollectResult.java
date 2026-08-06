package com.datanest.task.core.collect;

import lombok.Data;

@Data
public class CollectResult {

    private int dbCount;

    private int tableCount;

    private int columnCount;

    private int addedTableCount;

    private int updatedTableCount;

    private int deletedTableCount;

    private int addedColumnCount;

    private int updatedColumnCount;

    private int deletedColumnCount;
}
