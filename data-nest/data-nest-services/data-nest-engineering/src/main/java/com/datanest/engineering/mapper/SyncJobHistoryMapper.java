package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.SyncJobHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SyncJobHistoryMapper extends BaseMapper<SyncJobHistory> {
}
