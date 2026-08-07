package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.QualityRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质量规则 Mapper（Sprint 6 配置层）。
 */
@Mapper
public interface QualityRuleMapper extends BaseMapper<QualityRule> {
}
