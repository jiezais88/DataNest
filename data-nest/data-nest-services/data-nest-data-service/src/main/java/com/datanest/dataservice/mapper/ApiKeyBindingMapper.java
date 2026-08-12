package com.datanest.dataservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.dataservice.dto.RefCount;
import com.datanest.dataservice.entity.ApiKeyBinding;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Key-API 绑定 Mapper。
 */
public interface ApiKeyBindingMapper extends BaseMapper<ApiKeyBinding> {

    /** 批量统计每个 API 绑定的 Key 数（API 列表/详情用，避免逐条查询） */
    @Select("<script>SELECT api_id AS refId, COUNT(*) AS cnt FROM api_key_binding WHERE api_id IN "
            + "<foreach collection='apiIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY api_id</script>")
    List<RefCount> countKeysByApiIds(@Param("apiIds") List<Long> apiIds);

    /** 批量统计每个 Key 绑定的 API 数（Key 列表用，避免逐条查询） */
    @Select("<script>SELECT key_id AS refId, COUNT(*) AS cnt FROM api_key_binding WHERE key_id IN "
            + "<foreach collection='keyIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY key_id</script>")
    List<RefCount> countApisByKeyIds(@Param("keyIds") List<Long> keyIds);
}
