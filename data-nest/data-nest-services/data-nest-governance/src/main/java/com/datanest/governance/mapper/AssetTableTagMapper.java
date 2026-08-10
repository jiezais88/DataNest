package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.AssetTableTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AssetTableTagMapper extends BaseMapper<AssetTableTag> {

    /**
     * 按表 ID 集合批量查标签绑定（table_id/tag_id/tag_name），资产搜索/浏览回填 tags 用（避免 N+1）。
     * 注意别名用下划线小写：PostgreSQL 会把未加引号的驼峰别名折叠成小写。
     */
    @Select("""
            <script>
            SELECT b.table_id AS table_id, b.tag_id AS tag_id, t.name AS tag_name
            FROM asset_table_tag b
                     JOIN asset_tag t ON t.id = b.tag_id
            WHERE b.table_id IN
            <foreach collection="tableIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            ORDER BY b.table_id, t.name
            </script>
            """)
    List<Map<String, Object>> selectTagRowsByTableIds(@Param("tableIds") List<Long> tableIds);
}
