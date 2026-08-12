package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.dto.DataSourceStatsDTO;
import com.datanest.engineering.entity.DataSourceConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DataSourceConnectionMapper extends BaseMapper<DataSourceConnection> {

    /**
     * 连接状态统计（列表页顶部统计卡），避免前端拉全量列表计数。
     */
    @Select("SELECT " +
            "  COUNT(*) FILTER (WHERE status = 'NORMAL') AS normal, " +
            "  COUNT(*) FILTER (WHERE status = 'ERROR') AS error, " +
            "  COUNT(*) FILTER (WHERE status = 'OFFLINE') AS offline, " +
            "  COUNT(*) FILTER (WHERE status = 'UNKNOWN') AS unknown " +
            "FROM datasource_connection")
    DataSourceStatsDTO selectStats();
}
