package com.datanest.engineering.api;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.DagEdgeInfo;
import com.datanest.engineering.api.dto.DagInfo;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.engineering.api.dto.DagParamInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.engineering.api.fallback.EngineeringDagApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * DAG 定义域内部 Feign 契约（worker 节点执行读取定义）。
 */
@FeignClient(name = "data-nest-engineering", path = "/engineering/internal", contextId = "engineeringDagApi",
        fallbackFactory = EngineeringDagApiFallbackFactory.class)
public interface EngineeringDagApi {

    /** 按 id 查询 DAG 定义 */
    @GetMapping("/dags/{id}")
    Result<DagInfo> getById(@PathVariable("id") Long id);

    /** 按 id 列表批量查询 DAG */
    @PostMapping("/dags/batch")
    Result<Map<Long, DagInfo>> batchGet(@RequestBody IdsRequest request);

    /** DAG 全部节点（执行所需全量配置） */
    @GetMapping("/dags/{id}/nodes")
    Result<List<DagNodeInfo>> listNodes(@PathVariable("id") Long id);

    /** 按 nodeId 查单节点配置 */
    @GetMapping("/dags/{id}/nodes/by-node-id")
    Result<DagNodeInfo> getNodeByNodeId(@PathVariable("id") Long id, @RequestParam("nodeId") String nodeId);

    /** DAG 全部边 */
    @GetMapping("/dags/{id}/edges")
    Result<List<DagEdgeInfo>> listEdges(@PathVariable("id") Long id);

    /** DAG 自定义参数 */
    @GetMapping("/dags/{id}/parameters")
    Result<List<DagParamInfo>> listParameters(@PathVariable("id") Long id);
}
