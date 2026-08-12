package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.dto.DagVersionPayload;
import com.datanest.engineering.entity.DagEdge;
import com.datanest.engineering.entity.DagNode;
import com.datanest.engineering.entity.DagParameter;
import com.datanest.engineering.entity.DagVersion;
import com.datanest.engineering.mapper.DagEdgeMapper;
import com.datanest.engineering.mapper.DagNodeMapper;
import com.datanest.engineering.mapper.DagParameterMapper;
import com.datanest.engineering.mapper.DagVersionMapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.task.core.support.SystemUserResolver;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * DAG 版本服务
 * - 保存时自动生成版本快照
 * - 版本列表、对比、回滚
 */
@Service
public class DagVersionService {

    private static final Logger logger = LoggerFactory.getLogger(DagVersionService.class);

    private final DagVersionMapper dagVersionMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DagParameterMapper dagParameterMapper;
    private final SystemUserApi systemUserApi;

    public DagVersionService(DagVersionMapper dagVersionMapper, DagNodeMapper dagNodeMapper,
                             DagEdgeMapper dagEdgeMapper, DagParameterMapper dagParameterMapper,
                             SystemUserApi systemUserApi) {
        this.dagVersionMapper = dagVersionMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dagParameterMapper = dagParameterMapper;
        this.systemUserApi = systemUserApi;
    }

    /**
     * 为当前 DAG 生成新版本（在 DagService.update 事务内调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public DagVersion createVersion(Long dagId) {
        List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
        List<DagEdge> edges = dagEdgeMapper.selectByDagId(dagId);
        List<DagParameter> params = dagParameterMapper.selectByDagId(dagId);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("nodes", nodes);
        snapshot.put("edges", edges);
        snapshot.put("params", params);

        Integer maxVersion = dagVersionMapper.selectMaxVersionNo(dagId);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        String changeSummary = generateChangeSummary(dagId, maxVersion, nodes, edges, params);

        DagVersion version = new DagVersion();
        version.setDagId(dagId);
        version.setVersionNo(nextVersion);
        version.setSnapshot(JSON.toJSONString(snapshot));
        version.setChangeSummary(changeSummary);
        version.setCreatedBy(currentUserId());
        version.setCreatedAt(LocalDateTime.now());
        dagVersionMapper.insert(version);

        logger.info("生成 DAG 版本: dagId={}, versionNo={}", dagId, nextVersion);
        return version;
    }

    public List<DagVersionPayload> listVersions(Long dagId) {
        List<DagVersionPayload> payloads = dagVersionMapper.selectByDagId(dagId).stream()
                .map(this::toPayload)
                .toList();
        fillCreatedByNames(payloads);
        return payloads;
    }

    public DagVersionPayload getVersion(Long dagId, Integer versionNo) {
        DagVersion v = dagVersionMapper.selectByDagIdAndVersionNo(dagId, versionNo);
        if (v == null) {
            throw new BusinessException(ErrorCode.DAG_VERSION_NOT_FOUND);
        }
        return toPayload(v);
    }

    /**
     * 对比两个版本，返回结构化的差异。
     */
    public DagVersionPayload.DagVersionDiff compare(Long dagId, Integer leftNo, Integer rightNo) {
        DagVersion left = dagVersionMapper.selectByDagIdAndVersionNo(dagId, leftNo);
        DagVersion right = dagVersionMapper.selectByDagIdAndVersionNo(dagId, rightNo);
        if (left == null || right == null) {
            throw new BusinessException(ErrorCode.DAG_VERSION_NOT_FOUND);
        }
        Snapshot leftSnap = parseSnapshot(left.getSnapshot());
        Snapshot rightSnap = parseSnapshot(right.getSnapshot());
        return buildDiff(leftSnap, rightSnap);
    }

    /**
     * 回滚到指定版本：清空当前节点/边/参数，恢复快照，并生成新版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public DagVersionPayload rollback(Long dagId, Integer targetVersionNo) {
        DagVersion target = dagVersionMapper.selectByDagIdAndVersionNo(dagId, targetVersionNo);
        if (target == null) {
            throw new BusinessException(ErrorCode.DAG_VERSION_NOT_FOUND);
        }

        Snapshot snap = parseSnapshot(target.getSnapshot());

        dagNodeMapper.delete(new QueryWrapper<DagNode>().eq("dag_id", dagId));
        dagEdgeMapper.delete(new QueryWrapper<DagEdge>().eq("dag_id", dagId));
        dagParameterMapper.delete(new QueryWrapper<DagParameter>().eq("dag_id", dagId));

        LocalDateTime now = LocalDateTime.now();
        Long operatorId = currentUserId();

        if (snap.nodes != null) {
            for (DagNode node : snap.nodes) {
                node.setId(null);
                node.setDagId(dagId);
                node.setCreatedBy(operatorId);
                node.setUpdatedBy(operatorId);
                node.setCreatedAt(now);
                node.setUpdatedAt(now);
                dagNodeMapper.insert(node);
            }
        }
        if (snap.edges != null) {
            for (DagEdge edge : snap.edges) {
                edge.setId(null);
                edge.setDagId(dagId);
                edge.setCreatedBy(operatorId);
                edge.setCreatedAt(now);
                dagEdgeMapper.insert(edge);
            }
        }
        if (snap.params != null) {
            for (DagParameter param : snap.params) {
                param.setId(null);
                param.setDagId(dagId);
                param.setCreatedBy(operatorId);
                param.setUpdatedBy(operatorId);
                param.setCreatedAt(now);
                param.setUpdatedAt(now);
                dagParameterMapper.insert(param);
            }
        }

        logger.info("DAG 回滚完成: dagId={}, targetVersionNo={}", dagId, targetVersionNo);
        return toPayload(createVersion(dagId));
    }

    private String generateChangeSummary(Long dagId, Integer previousVersionNo,
                                         List<DagNode> nodes, List<DagEdge> edges, List<DagParameter> params) {
        if (previousVersionNo == null || previousVersionNo <= 0) {
            return "初始版本";
        }
        DagVersion previous = dagVersionMapper.selectByDagIdAndVersionNo(dagId, previousVersionNo);
        if (previous == null) {
            return "初始版本";
        }
        Snapshot prev = parseSnapshot(previous.getSnapshot());
        DagVersionPayload.DagVersionDiff diff = buildDiff(prev, new Snapshot(nodes, edges, params));

        List<String> parts = new ArrayList<>();
        int nodeChanges = count(diff.getAddedNodes()) + count(diff.getRemovedNodes()) + count(diff.getModifiedNodes());
        int edgeChanges = count(diff.getAddedEdges()) + count(diff.getRemovedEdges());
        int paramChanges = count(diff.getAddedParams()) + count(diff.getRemovedParams()) + count(diff.getModifiedParams());
        if (nodeChanges > 0) parts.add("节点变化 " + nodeChanges);
        if (edgeChanges > 0) parts.add("连线变化 " + edgeChanges);
        if (paramChanges > 0) parts.add("参数变化 " + paramChanges);
        return parts.isEmpty() ? "无变更" : String.join("，", parts);
    }

    private int count(List<String> list) {
        return list == null ? 0 : list.size();
    }

    private DagVersionPayload.DagVersionDiff buildDiff(Snapshot left, Snapshot right) {
        DagVersionPayload.DagVersionDiff diff = new DagVersionPayload.DagVersionDiff();

        Map<String, String> leftNodes = nodeMap(left.nodes);
        Map<String, String> rightNodes = nodeMap(right.nodes);
        diff.setAddedNodes(new ArrayList<>(difference(rightNodes.keySet(), leftNodes.keySet())));
        diff.setRemovedNodes(new ArrayList<>(difference(leftNodes.keySet(), rightNodes.keySet())));
        diff.setModifiedNodes(new ArrayList<>());
        for (String id : leftNodes.keySet()) {
            if (rightNodes.containsKey(id) && !Objects.equals(leftNodes.get(id), rightNodes.get(id))) {
                diff.getModifiedNodes().add(id);
            }
        }

        Set<String> leftEdges = edgeSet(left.edges);
        Set<String> rightEdges = edgeSet(right.edges);
        diff.setAddedEdges(new ArrayList<>(difference(rightEdges, leftEdges)));
        diff.setRemovedEdges(new ArrayList<>(difference(leftEdges, rightEdges)));

        Map<String, String> leftParams = paramMap(left.params);
        Map<String, String> rightParams = paramMap(right.params);
        diff.setAddedParams(new ArrayList<>(difference(rightParams.keySet(), leftParams.keySet())));
        diff.setRemovedParams(new ArrayList<>(difference(leftParams.keySet(), rightParams.keySet())));
        diff.setModifiedParams(new ArrayList<>());
        for (String name : leftParams.keySet()) {
            if (rightParams.containsKey(name) && !Objects.equals(leftParams.get(name), rightParams.get(name))) {
                diff.getModifiedParams().add(name);
            }
        }

        return diff;
    }

    private Map<String, String> nodeMap(List<DagNode> nodes) {
        Map<String, String> map = new HashMap<>();
        if (nodes == null) return map;
        for (DagNode n : nodes) {
            map.put(n.getNodeId(), n.getNodeName() + "|" + n.getNodeType() + "|" + n.getConfig());
        }
        return map;
    }

    private Set<String> edgeSet(List<DagEdge> edges) {
        Set<String> set = new HashSet<>();
        if (edges == null) return set;
        for (DagEdge e : edges) {
            set.add(e.getSourceNodeId() + "->" + e.getTargetNodeId());
        }
        return set;
    }

    private Map<String, String> paramMap(List<DagParameter> params) {
        Map<String, String> map = new HashMap<>();
        if (params == null) return map;
        for (DagParameter p : params) {
            map.put(p.getParamName(), p.getParamType() + "|" + p.getDefaultValue() + "|" + p.getRequired());
        }
        return map;
    }

    private Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> diff = new HashSet<>(a);
        diff.removeAll(b);
        return diff;
    }

    private Snapshot parseSnapshot(String json) {
        if (!org.springframework.util.StringUtils.hasText(json)) {
            return new Snapshot(List.of(), List.of(), List.of());
        }
        JSONObject obj = JSON.parseObject(json);
        List<DagNode> nodes = obj.getList("nodes", DagNode.class);
        List<DagEdge> edges = obj.getList("edges", DagEdge.class);
        List<DagParameter> params = obj.getList("params", DagParameter.class);
        return new Snapshot(nodes, edges, params);
    }

    private DagVersionPayload toPayload(DagVersion v) {
        DagVersionPayload p = new DagVersionPayload();
        p.setId(v.getId());
        p.setDagId(v.getDagId());
        p.setVersionNo(v.getVersionNo());
        p.setSnapshot(v.getSnapshot());
        p.setChangeSummary(v.getChangeSummary());
        p.setCreatedBy(v.getCreatedBy());
        p.setCreatedAt(v.getCreatedAt());
        return p;
    }

    private void fillCreatedByNames(List<DagVersionPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return;
        }
        List<Long> userIds = payloads.stream()
                .map(DagVersionPayload::getCreatedBy)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        Map<Long, String> usernameMap = usernames(userIds);
        for (DagVersionPayload p : payloads) {
            if (p.getCreatedBy() != null && p.getCreatedBy() > 0) {
                p.setCreatedByName(usernameMap.getOrDefault(p.getCreatedBy(), "-"));
            }
        }
    }

    private long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private record Snapshot(List<DagNode> nodes, List<DagEdge> edges, List<DagParameter> params) {
    }

    /**
     * 经 system 服务 Feign 批量查询 userId → username 映射（委托 task-core SystemUserResolver）。
     * system 不可用时降级为空 Map（列表页名称列退化为空），不拖垮本接口。
     */
    private Map<Long, String> usernames(Collection<Long> userIds) {
        return SystemUserResolver.usernames(systemUserApi, userIds);
    }
}
