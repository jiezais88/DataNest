package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.QualityJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质量任务 Mapper（Sprint 6 配置层）。
 */
@Mapper
public interface QualityJobMapper extends BaseMapper<QualityJob> {
}
