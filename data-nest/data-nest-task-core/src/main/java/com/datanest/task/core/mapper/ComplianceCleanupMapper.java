package com.datanest.task.core.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 合规检查结果清理 Mapper（仅做删除数据源时的级联清理）。
 * 注意：查询侧实体/Mapper 在 governance 模块（com.datanest.governance），
 * 此处故意只声明 @Delete 方法且不定义同名实体，避免与 governance 的
 * ComplianceCheckResultMapper  Bean 冲突。
 */
@Mapper
public interface ComplianceCleanupMapper {

    @Delete("DELETE FROM compliance_check_result WHERE datasource_id = #{datasourceId}")
    int deleteByDatasourceId(@Param("datasourceId") Long datasourceId);
}
