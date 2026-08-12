package com.datanest.dataservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.dataservice.dto.RefCount;
import com.datanest.dataservice.entity.ApiCallLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API 调用统计明细 Mapper（F3 异步写入；F2 用于近 7 天调用聚合，识别僵尸 Key / API 热度）。
 */
public interface ApiCallLogMapper extends BaseMapper<ApiCallLog> {

    /** 批量统计每个 Key 自 since 以来的调用数（Key 列表「近 7 天调用」列，0 = 僵尸 Key） */
    @Select("<script>SELECT key_id AS refId, COUNT(*) AS cnt FROM api_call_log WHERE created_at &gt;= #{since} AND key_id IN "
            + "<foreach collection='keyIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY key_id</script>")
    List<RefCount> countCallsByKeyIdsSince(@Param("keyIds") List<Long> keyIds, @Param("since") LocalDateTime since);

    /** 批量统计每个 API 自 since 以来的调用数（API 列表/详情「近 7 天调用」） */
    @Select("<script>SELECT api_id AS refId, COUNT(*) AS cnt FROM api_call_log WHERE created_at &gt;= #{since} AND api_id IN "
            + "<foreach collection='apiIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY api_id</script>")
    List<RefCount> countCallsByApiIdsSince(@Param("apiIds") List<Long> apiIds, @Param("since") LocalDateTime since);

    /** 统计 since 以来的全部调用数（API 管理列表页「近 7 天总调用」统计卡） */
    @Select("<script>SELECT COUNT(*) FROM api_call_log WHERE created_at &gt;= #{since}</script>")
    Long countCallsSince(@Param("since") LocalDateTime since);
}
