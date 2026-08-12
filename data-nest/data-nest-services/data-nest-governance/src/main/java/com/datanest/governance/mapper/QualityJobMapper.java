package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.dto.QualityJobStatsDTO;
import com.datanest.governance.entity.QualityJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 质量任务 Mapper（Sprint 6 配置层）。
 */
@Mapper
public interface QualityJobMapper extends BaseMapper<QualityJob> {

    /**
     * 任务配置统计（列表页顶部统计卡），避免前端拉全量列表计数。
     * 已停用 = enabled != 1（含未配置场景），与前端 disabled 语义一致。
     */
    @Select("SELECT " +
            "  COUNT(*) FILTER (WHERE enabled = 1) AS enabled, " +
            "  COUNT(*) FILTER (WHERE enabled IS DISTINCT FROM 1) AS disabled, " +
            "  COUNT(*) FILTER (WHERE scheduled_enabled = 1) AS scheduled, " +
            "  COUNT(*) FILTER (WHERE auto_trigger_enabled = 1) AS auto " +
            "FROM quality_job")
    QualityJobStatsDTO selectStats();
}
