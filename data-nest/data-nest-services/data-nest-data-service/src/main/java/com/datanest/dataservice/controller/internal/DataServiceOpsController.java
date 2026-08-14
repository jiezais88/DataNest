package com.datanest.dataservice.controller.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanest.common.model.Result;
import com.datanest.dataservice.api.dto.CleanupRequest;
import com.datanest.dataservice.api.dto.DisableApisByTableRequest;
import com.datanest.dataservice.entity.SqlQueryHistory;
import com.datanest.dataservice.mapper.SqlQueryHistoryMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据服务内部运维端点（Sprint 10 F1，实现 data-service-api 的 DataServiceOpsApi 契约）。
 * <p>
 * 仅供 app-job 定时调度触发（本地禁止 @Scheduled，统一 job 侧 cron），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@Hidden // 内部 Feign 契约端点，不进接口文档
@RestController
@RequestMapping("/internal")
public class DataServiceOpsController {

    private static final Logger logger = LoggerFactory.getLogger(DataServiceOpsController.class);


    private final SqlQueryHistoryMapper historyMapper;
    private final com.datanest.dataservice.service.DataApiService dataApiService;
    /** 查询历史保留天数（默认 30） */
    @Value("${datanest.dataservice.history.retention-days:30}")
    private Integer retentionDays;

    public DataServiceOpsController(SqlQueryHistoryMapper historyMapper,
                                    com.datanest.dataservice.service.DataApiService dataApiService) {
        this.historyMapper = historyMapper;
        this.dataApiService = dataApiService;
    }

    /**
     * 清理超过保留天数的 SQL 查询历史，返回删除条数。
     */
    @PostMapping("/sql-history/cleanup")
    public Result<Integer> cleanupSqlQueryHistory(@RequestBody CleanupRequest request) {
        int retain = request.getRetainDays() != null && request.getRetainDays() > 0
                ? request.getRetainDays() : retentionDays;
        LocalDateTime boundary = LocalDateTime.now().minusDays(retain);
        int deleted = historyMapper.delete(new LambdaQueryWrapper<SqlQueryHistory>()
                .lt(SqlQueryHistory::getCreatedAt, boundary));
        logger.info("SQL 查询历史清理完成: 删除 {} 行（早于 {}，保留 {} 天）", deleted, boundary, retain);
        return Result.ok(deleted);
    }

    @PostMapping("/api-disable-by-tables")
    public Result<Integer> disableApisByMetadataTableIds(@RequestBody DisableApisByTableRequest request) {
        List<Long> ids = request.getMetadataTableIds();
        int count = dataApiService.disableByMetadataTableIds(ids);
        logger.info("联动下线 API（机密改级）: tables={}, disabled={}", ids, count);
        return Result.ok(count);
    }
}
