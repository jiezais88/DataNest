package com.datanest.engineering.api;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.ObjectNameRequest;
import com.datanest.engineering.api.dto.ObjectOptionDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 工程服务内部 Feign 契约。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-engineering 的 /engineering/internal/** 端点。
 */
@FeignClient(name = "data-nest-engineering", path = "/engineering/internal", contextId = "engineeringObjectApi")
public interface EngineeringObjectApi {

    /** 按对象类型 + ID 列表批量查询对象名称 */
    @PostMapping("/objects/names")
    Result<Map<Long, String>> names(@RequestBody ObjectNameRequest request);

    /** 按对象类型查询告警对象下拉选项 */
    @GetMapping("/alert-objects/options")
    Result<List<ObjectOptionDTO>> options(@RequestParam("objectType") String objectType);

    /** 按节点标识查询 DAG 节点 ID */
    @GetMapping("/dags/{dagId}/nodes/by-node-id")
    Result<Long> findDagNodeId(@PathVariable("dagId") Long dagId, @RequestParam("nodeId") String nodeId);
}
