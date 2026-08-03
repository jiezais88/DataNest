package com.datanest.governance.dto;

import lombok.Data;

/**
 * 血缘图谱节点（表节点）。
 */
@Data
public class LineageNodeDTO {

    /** 唯一 ID：库名.表名 全名 */
    private String id;

    /** 展示名：库名.表名 */
    private String name;

    /** 库名（表名无 schema 时为空） */
    private String database;

    /** 血缘类型：SQL / SYNC / PYTHON */
    private String type;

    /** 是否当前查询的表 */
    private boolean current;

    public LineageNodeDTO(String id, String name, String database, String type, boolean current) {
        this.id = id;
        this.name = name;
        this.database = database;
        this.type = type;
        this.current = current;
    }
}
