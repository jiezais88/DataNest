package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.NamingStandard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NamingStandardMapper extends BaseMapper<NamingStandard> {

    @Select("SELECT * FROM naming_standard WHERE enabled = 1 AND applies_to = #{appliesTo} ORDER BY priority DESC, id ASC")
    List<NamingStandard> selectEnabledByAppliesTo(String appliesTo);
}
