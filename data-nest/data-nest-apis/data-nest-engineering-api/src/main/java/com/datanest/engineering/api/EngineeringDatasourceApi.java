package com.datanest.engineering.api;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.DataSourceStatusUpdateRequest;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.engineering.api.fallback.EngineeringDatasourceApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 数据源域内部 Feign 契约（worker/job/governance 读取 + job 状态刷新）。
 */
@FeignClient(name = "data-nest-engineering", path = "/engineering/internal", contextId = "engineeringDatasourceApi",
        fallbackFactory = EngineeringDatasourceApiFallbackFactory.class)
public interface EngineeringDatasourceApi {

    /** 按 id 查询数据源（全字段含 encryptedPassword，内部端点） */
    @GetMapping("/datasources/{id}")
    Result<DataSourceInfo> getById(@PathVariable("id") Long id);

    /** 按 id 列表批量查询数据源 */
    @PostMapping("/datasources/batch")
    Result<Map<Long, DataSourceInfo>> batchGet(@RequestBody IdsRequest request);

    /** 查询活跃数据源（status IN NORMAL/ERROR，job 定时刷新用） */
    @GetMapping("/datasources/active")
    Result<List<DataSourceInfo>> listActive();

    /** 回写数据源连接状态（status/errorMessage/lastTestTime） */
    @PutMapping("/datasources/{id}/status")
    Result<Void> updateStatus(@PathVariable("id") Long id, @RequestBody DataSourceStatusUpdateRequest request);

    /** 刷新全部活跃数据源连接状态（逐个连接测试 + 状态回写；job 定时刷新用） */
    @PostMapping("/datasources/refresh-statuses")
    Result<Void> refreshStatuses();
}
