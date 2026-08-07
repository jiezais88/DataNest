package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.FieldTypeStandard;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字段类型标准 Mapper。从 governance 模块下沉至共享底座。
 */
@Mapper
public interface FieldTypeStandardMapper extends BaseMapper<FieldTypeStandard> {
}
