package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.ComplianceCheckResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 标准合规检查结果 Mapper。从 governance 模块下沉至共享底座。
 * 与 {@link ComplianceCleanupMapper}（仅 @Delete）职责互补、Bean 名不同，互不冲突。
 */
@Mapper
public interface ComplianceCheckResultMapper extends BaseMapper<ComplianceCheckResult> {

    /**
     * 真正的批量插入，避免检查结果逐条 insert；
     * id 由服务层用 IdWorker 预生成，applicableStandards 走 JacksonTypeHandler 序列化为 JSON，
     * ignored 由数据库默认 0（未忽略）。
     */
    @Insert("<script>" +
            "INSERT INTO compliance_check_result (id, standard_id, standard_name, object_type, datasource_id, database_name, schema_name, table_id, column_id, object_name, object_path, violation_type, actual_value, expected_value, applicable_standards, is_compliant, checked_at, ignored) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.standardId}, #{item.standardName}, #{item.objectType}, #{item.datasourceId}, #{item.databaseName}, #{item.schemaName}, #{item.tableId}, #{item.columnId}, #{item.objectName}, #{item.objectPath}, #{item.violationType}, #{item.actualValue}, #{item.expectedValue}, #{item.applicableStandards,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler}, #{item.isCompliant}, #{item.checkedAt}, 0)" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<ComplianceCheckResult> list);
}
