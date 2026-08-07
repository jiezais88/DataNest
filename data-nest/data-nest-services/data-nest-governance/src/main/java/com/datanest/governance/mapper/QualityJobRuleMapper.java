package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.QualityJobRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质量任务-规则 关联 Mapper（Sprint 7 规则独立化）。
 */
@Mapper
public interface QualityJobRuleMapper extends BaseMapper<QualityJobRule> {
}
