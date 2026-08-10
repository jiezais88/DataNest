package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.AssetTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AssetTagMapper extends BaseMapper<AssetTag> {

    /**
     * 标签云：全部标签 + 各标签绑定的表数（asset_table_tag 计数）。
     * 注意别名用下划线小写：PostgreSQL 会把未加引号的驼峰别名折叠成小写，导致 map key 取不到值。
     */
    @Select("""
            SELECT t.id AS id, t.name AS name, COUNT(b.id) AS ref_count
            FROM asset_tag t
                     LEFT JOIN asset_table_tag b ON b.tag_id = t.id
            GROUP BY t.id, t.name
            ORDER BY ref_count DESC, t.name
            """)
    List<Map<String, Object>> selectTagCloud();

    /** 删除无任何表绑定的孤儿标签字典行（表删除级联清理后调用）。 */
    @Delete("DELETE FROM asset_tag WHERE id NOT IN (SELECT DISTINCT tag_id FROM asset_table_tag)")
    int deleteOrphanTags();
}
