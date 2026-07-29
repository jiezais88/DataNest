package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.SyncJobLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SyncJobLogMapper extends BaseMapper<SyncJobLog> {
}
