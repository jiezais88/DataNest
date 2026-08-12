package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.dto.SyncJobStatsDTO;
import com.datanest.engineering.entity.SyncJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SyncJobMapper extends BaseMapper<SyncJob> {

    @Select("SELECT * FROM sync_job WHERE source_datasource_id = #{datasourceId}")
    List<SyncJob> selectBySourceDatasourceId(@Param("datasourceId") Long datasourceId);

    /**
     * 任务状态统计（列表页顶部统计卡），避免前端拉全量列表计数。
     * 各状态与列表筛选项一一对应（FAILED / TERMINATED 分开统计，保证下钻数字对得上）。
     */
    @Select("SELECT " +
            "  COUNT(*) FILTER (WHERE execution_status = 'RUNNING') AS running, " +
            "  COUNT(*) FILTER (WHERE execution_status = 'SUCCESS') AS success, " +
            "  COUNT(*) FILTER (WHERE execution_status = 'FAILED') AS failed, " +
            "  COUNT(*) FILTER (WHERE execution_status = 'TERMINATED') AS terminated, " +
            "  COUNT(*) FILTER (WHERE execution_status = 'PENDING') AS pending " +
            "FROM sync_job")
    SyncJobStatsDTO selectStats();
}
