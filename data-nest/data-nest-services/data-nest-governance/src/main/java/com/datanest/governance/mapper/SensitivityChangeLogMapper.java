package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.SensitivityChangeLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据分级变更审计 Mapper（Sprint 10 F5）。
 */
@Mapper
public interface SensitivityChangeLogMapper extends BaseMapper<SensitivityChangeLog> {
}
