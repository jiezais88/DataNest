package com.datanest.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.system.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;

/**
 * 审计日志 Mapper（Sprint 11 F1）。
 * <p>
 * 业务写入/查询走 MyBatis-Plus BaseMapper；仅清理接口提供按时间删除（90 天保留，job/internal 调用）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 删除指定时间之前的审计记录（仅清理任务调用，非业务删除入口） */
    @Delete("DELETE FROM audit_log WHERE created_at < #{before}")
    int deleteBefore(@Param("before") java.time.LocalDateTime before);
}
