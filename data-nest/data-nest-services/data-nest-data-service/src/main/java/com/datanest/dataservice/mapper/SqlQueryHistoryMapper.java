package com.datanest.dataservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.dataservice.entity.SqlQueryHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SqlQueryHistoryMapper extends BaseMapper<SqlQueryHistory> {
}
