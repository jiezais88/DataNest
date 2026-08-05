package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.QualityScore;
import org.apache.ibatis.annotations.Mapper;

/**
 * 表级质量评分 Mapper（Sprint 6 NG8）。
 * <p>
 * 批量按表名 IN 查询由调用方用 {@code QueryWrapper.in("table_name", ...)} 实现，
 * 血缘图谱回填时一次查询，避免 N+1。
 */
@Mapper
public interface QualityScoreMapper extends BaseMapper<QualityScore> {
}
