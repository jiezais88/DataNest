package com.datanest.engineering.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.DataSourceStatusUpdateRequest;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.engineering.service.internal.InternalDatasourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源域内部接口（实现 engineering-api 的 EngineeringDatasourceApi 契约）。
 * <p>
 * 仅供服务间内部调用（worker 建 JDBC 读取连接信息、job 定时刷新状态），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal/datasources")
public class InternalDatasourceController {

    private final InternalDatasourceService datasourceService;

    public InternalDatasourceController(InternalDatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    /** 按 id 查询数据源（全字段含 encryptedPassword，内部端点） */
    @GetMapping("/{id}")
    public Result<DataSourceInfo> getById(@PathVariable Long id) {
        return Result.ok(datasourceService.getById(id));
    }

    /** 按 id 列表批量查询数据源 */
    @PostMapping("/batch")
    public Result<Map<Long, DataSourceInfo>> batchGet(@RequestBody IdsRequest request) {
        return Result.ok(datasourceService.batchGet(request == null ? null : request.getIds()));
    }

    /** 活跃数据源（status IN NORMAL/ERROR，job 定时刷新用） */
    @GetMapping("/active")
    public Result<List<DataSourceInfo>> listActive() {
        return Result.ok(datasourceService.listActive());
    }

    /** 回写数据源连接状态 */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody DataSourceStatusUpdateRequest request) {
        datasourceService.updateStatus(id, request);
        return Result.ok(null);
    }
}
