package com.datanest.engineering.controller.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.ObjectNameRequest;
import com.datanest.engineering.api.dto.ObjectOptionDTO;
import com.datanest.engineering.entity.Dag;
import com.datanest.engineering.entity.DagNode;
import com.datanest.engineering.entity.DagProject;
import com.datanest.engineering.entity.SyncJob;
import com.datanest.engineering.mapper.DagMapper;
import com.datanest.engineering.mapper.DagNodeMapper;
import com.datanest.engineering.mapper.DagProjectMapper;
import com.datanest.engineering.mapper.SyncJobMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 工程域对象内部接口（实现 engineering-api 的 Feign 契约）。
 * <p>
 * 仅供服务间内部调用（如告警服务解析对象名称/下拉选项/dag_node.id），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal")
public class InternalObjectController {

    private static final String OBJECT_TYPE_DAG = "DAG";
    private static final String OBJECT_TYPE_SYNC_JOB = "SYNC_JOB";

    private final DagMapper dagMapper;
    private final DagProjectMapper dagProjectMapper;
    private final DagNodeMapper dagNodeMapper;
    private final SyncJobMapper syncJobMapper;

    public InternalObjectController(DagMapper dagMapper,
                                    DagProjectMapper dagProjectMapper,
                                    DagNodeMapper dagNodeMapper,
                                    SyncJobMapper syncJobMapper) {
        this.dagMapper = dagMapper;
        this.dagProjectMapper = dagProjectMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.syncJobMapper = syncJobMapper;
    }

    /**
     * 按对象类型 + ID 列表批量查询对象名称；不支持的类型返回空 map。
     */
    @PostMapping("/objects/names")
    public Result<Map<Long, String>> names(@RequestBody ObjectNameRequest request) {
        String objectType = request.getObjectType() == null ? "" : request.getObjectType().toUpperCase();
        List<Long> ids = request.getIds() == null ? Collections.emptyList()
                : request.getIds().stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return Result.ok(names);
        }
        if (OBJECT_TYPE_DAG.equals(objectType)) {
            for (Dag dag : dagMapper.selectBatchIds(ids)) {
                names.put(dag.getId(), dag.getName());
            }
        } else if (OBJECT_TYPE_SYNC_JOB.equals(objectType)) {
            for (SyncJob job : syncJobMapper.selectBatchIds(ids)) {
                names.put(job.getId(), job.getName());
            }
        }
        return Result.ok(names);
    }

    /**
     * 按对象类型查询告警对象下拉选项：DAG 按「项目 → DAG」树形返回；SYNC_JOB 平铺返回。
     * 不支持的类型返回空列表。
     */
    @GetMapping("/alert-objects/options")
    public Result<List<ObjectOptionDTO>> options(@RequestParam String objectType) {
        String type = objectType == null ? "" : objectType.toUpperCase();
        if (OBJECT_TYPE_DAG.equals(type)) {
            List<DagProject> projects = dagProjectMapper.selectList(null);
            List<Dag> dags = dagMapper.selectList(null);
            Map<Long, List<Dag>> dagByProject = new HashMap<>();
            for (Dag dag : dags) {
                dagByProject.computeIfAbsent(dag.getProjectId(), k -> new ArrayList<>()).add(dag);
            }
            List<ObjectOptionDTO> tree = new ArrayList<>();
            for (DagProject project : projects) {
                ObjectOptionDTO option = new ObjectOptionDTO();
                option.setId(project.getId());
                option.setName(project.getName());
                option.setChildren(dagByProject.getOrDefault(project.getId(), Collections.emptyList())
                        .stream()
                        .map(d -> {
                            ObjectOptionDTO child = new ObjectOptionDTO();
                            child.setId(d.getId());
                            child.setName(d.getName());
                            return child;
                        })
                        .toList());
                tree.add(option);
            }
            return Result.ok(tree);
        }
        if (OBJECT_TYPE_SYNC_JOB.equals(type)) {
            List<ObjectOptionDTO> list = syncJobMapper.selectList(new QueryWrapper<SyncJob>().select("id", "name"))
                    .stream()
                    .map(s -> {
                        ObjectOptionDTO option = new ObjectOptionDTO();
                        option.setId(s.getId());
                        option.setName(s.getName());
                        return option;
                    })
                    .toList();
            return Result.ok(list);
        }
        return Result.ok(Collections.emptyList());
    }

    /**
     * 批量解析节点标识 → dag_node.id 映射（一次 IN 查询）；查不到的节点不出现在 map 中。
     */
    @PostMapping("/dags/{dagId}/nodes/resolve")
    public Result<Map<String, Long>> resolveDagNodeIds(@PathVariable Long dagId, @RequestBody List<String> nodeIds) {
        Map<String, Long> resolved = new HashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Result.ok(resolved);
        }
        List<String> validNodeIds = nodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (validNodeIds.isEmpty()) {
            return Result.ok(resolved);
        }
        for (DagNode dagNode : dagNodeMapper.selectList(new QueryWrapper<DagNode>()
                .select("id", "node_id").eq("dag_id", dagId).in("node_id", validNodeIds))) {
            resolved.put(dagNode.getNodeId(), dagNode.getId());
        }
        return Result.ok(resolved);
    }
}
