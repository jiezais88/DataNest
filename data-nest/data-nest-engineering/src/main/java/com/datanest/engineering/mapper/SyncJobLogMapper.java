package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.SyncJobLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SyncJobLogMapper extends BaseMapper<SyncJobLog> {
}
