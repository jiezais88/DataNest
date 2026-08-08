package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务模板（Sprint 7 DD-09）。
 * <p>
 * type：SYNC 数据同步 / COLLECT 元数据采集；category：BUILTIN 内置（禁改禁删）/ CUSTOM 自定义。
 * config_template 为 JSON 字符串：{"placeholders":[{key,label,required,valueType,defaultValue}],"config":{...}}。
 */
@Data
@TableName("task_template")
public class TaskTemplate {

    public static final String TYPE_SYNC = "SYNC";
    public static final String TYPE_COLLECT = "COLLECT";
    public static final String CATEGORY_BUILTIN = "BUILTIN";
    public static final String CATEGORY_CUSTOM = "CUSTOM";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String type;

    private String category;

    private String description;

    private String configTemplate;

    private Integer enabled;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
