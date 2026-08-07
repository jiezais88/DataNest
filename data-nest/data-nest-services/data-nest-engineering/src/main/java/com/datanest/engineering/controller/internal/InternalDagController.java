package com.datanest.engineering.controller.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.DagEdgeInfo;
import com.datanest.engineering.api.dto.DagInfo;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.engineering.api.dto.DagParamInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.engineering.entity.Dag;
import com.datanest.engineering.entity.DagEdge;
import com.datanest.engineering.entity.DagNode;
import com.datanest.engineering.entity.DagParameter;
import com.datanest.engineering.mapper.DagEdgeMapper;
import com.datanest.engineering.mapper.DagMapper;
import com.datanest.engineering.mapper.DagNodeMapper;
import com.datanest.engineering.mapper.DagParameterMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DAG 定义域内部接口（实现 engineering-api 的 EngineeringDagApi 契约，worker 节点执行读取定义）。
 * <p>
 * 均为只读简单查询，直接在 Controller 转发 mapper（与 InternalObjectController 风格一致）。
 */
@RestController
@RequestMapping("/internal/dags")
public class InternalDagController {

    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagParameterMapper dagParameterMapper;

    public InternalDagController(DagMapper dagMapper,
                                 DagNodeMapper dagNodeMapper,
                                 DagEdgeMapper dagEdgeMapper,
                                 DagParameterMapper dagParameterMapper) {
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dagParameterMapper = dagParameterMapper;
    }

    @GetMapping("/{id}")
    public Result<DagInfo> getById(@PathVariable Long id) {
        return Result.ok(toDagInfo(dagMapper.selectById(id)));
    }

    @PostMapping("/batch")
    public Result<Map<Long, DagInfo>> batchGet(@RequestBody IdsRequest request) {
        Map<Long, DagInfo> result = new HashMap<>();
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            return Result.ok(result);
        }
        List<Long> ids = request.getIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Result.ok(result);
        }
        for (Dag dag : dagMapper.selectBatchIds(ids)) {
            result.put(dag.getId(), toDagInfo(dag));
        }
        return Result.ok(result);
    }

    @GetMapping("/{id}/nodes")
    public Result<List<DagNodeInfo>> listNodes(@PathVariable Long id) {
        return Result.ok(dagNodeMapper.selectByDagId(id).stream().map(InternalDagController::toNodeInfo).toList());
    }

    @GetMapping("/{id}/nodes/by-node-id")
    public Result<DagNodeInfo> getNodeByNodeId(@PathVariable Long id, @RequestParam String nodeId) {
        DagNode node = dagNodeMapper.selectOne(new QueryWrapper<DagNode>()
                .eq("dag_id", id).eq("node_id", nodeId).last("LIMIT 1"));
        return Result.ok(toNodeInfo(node));
    }

    @GetMapping("/{id}/edges")
    public Result<List<DagEdgeInfo>> listEdges(@PathVariable Long id) {
        return Result.ok(dagEdgeMapper.selectByDagId(id).stream().map(InternalDagController::toEdgeInfo).toList());
    }

    @GetMapping("/{id}/parameters")
    public Result<List<DagParamInfo>> listParameters(@PathVariable Long id) {
        return Result.ok(dagParameterMapper.selectByDagId(id).stream().map(InternalDagController::toParamInfo).toList());
    }

    static DagInfo toDagInfo(Dag entity) {
        if (entity == null) {
            return null;
        }
        DagInfo info = new DagInfo();
        info.setId(entity.getId());
        info.setProjectId(entity.getProjectId());
        info.setName(entity.getName());
        info.setTriggerType(entity.getTriggerType());
        info.setCronExpression(entity.getCronExpression());
        info.setScheduleEnabled(entity.getScheduleEnabled());
        info.setMaxParallelism(entity.getMaxParallelism());
        info.setStatus(entity.getStatus());
        info.setDsProjectCode(entity.getDsProjectCode());
        info.setDsProcessDefinitionId(entity.getDsProcessDefinitionId());
        info.setDsProcessDefinitionCode(entity.getDsProcessDefinitionCode());
        info.setDsScheduleId(entity.getDsScheduleId());
        info.setReleaseState(entity.getReleaseState());
        info.setCreatedBy(entity.getCreatedBy());
        info.setUpdatedBy(entity.getUpdatedBy());
        info.setCreatedAt(entity.getCreatedAt());
        info.setUpdatedAt(entity.getUpdatedAt());
        return info;
    }

    static DagNodeInfo toNodeInfo(DagNode entity) {
        if (entity == null) {
            return null;
        }
        DagNodeInfo info = new DagNodeInfo();
        info.setId(entity.getId());
        info.setDagId(entity.getDagId());
        info.setNodeId(entity.getNodeId());
        info.setNodeName(entity.getNodeName());
        info.setNodeType(entity.getNodeType());
        info.setPositionX(entity.getPositionX());
        info.setPositionY(entity.getPositionY());
        info.setConfig(entity.getConfig());
        info.setDsTaskCode(entity.getDsTaskCode());
        info.setCreatedBy(entity.getCreatedBy());
        info.setUpdatedBy(entity.getUpdatedBy());
        info.setCreatedAt(entity.getCreatedAt());
        info.setUpdatedAt(entity.getUpdatedAt());
        return info;
    }

    static DagEdgeInfo toEdgeInfo(DagEdge entity) {
        if (entity == null) {
            return null;
        }
        DagEdgeInfo info = new DagEdgeInfo();
        info.setId(entity.getId());
        info.setDagId(entity.getDagId());
        info.setEdgeId(entity.getEdgeId());
        info.setSourceNodeId(entity.getSourceNodeId());
        info.setTargetNodeId(entity.getTargetNodeId());
        info.setCreatedBy(entity.getCreatedBy());
        info.setCreatedAt(entity.getCreatedAt());
        return info;
    }

    static DagParamInfo toParamInfo(DagParameter entity) {
        if (entity == null) {
            return null;
        }
        DagParamInfo info = new DagParamInfo();
        info.setId(entity.getId());
        info.setDagId(entity.getDagId());
        info.setParamName(entity.getParamName());
        info.setParamType(entity.getParamType());
        info.setDefaultValue(entity.getDefaultValue());
        info.setRequired(entity.getRequired());
        info.setDescription(entity.getDescription());
        return info;
    }
}
