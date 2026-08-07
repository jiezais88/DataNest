package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.SyncJobLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SyncJobLogMapper extends BaseMapper<SyncJobLog> {

    /** 批量插入日志（消除逐行 INSERT 写放大） */
    @Insert("<script>" +
            "INSERT INTO sync_job_log (id, history_id, sync_job_id, level, message, line_num, table_name, created_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.historyId}, #{item.syncJobId}, #{item.level}, #{item.message}, #{item.lineNum}, #{item.tableName}, #{item.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<SyncJobLog> list);
}
