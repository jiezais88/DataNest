package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.NamingStandard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 命名规范 Mapper。从 governance 模块下沉至共享底座。
 */
@Mapper
public interface NamingStandardMapper extends BaseMapper<NamingStandard> {

    @Select("SELECT * FROM naming_standard WHERE enabled = 1 AND applies_to = #{appliesTo} ORDER BY priority DESC, id ASC")
    List<NamingStandard> selectEnabledByAppliesTo(String appliesTo);
}
