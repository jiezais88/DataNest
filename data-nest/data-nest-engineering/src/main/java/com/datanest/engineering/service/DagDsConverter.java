package com.datanest.engineering.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.config.DolphinSchedulerConfig;
import com.datanest.engineering.dto.DagEdgePayload;
import com.datanest.engineering.dto.DagNodePayload;
import com.datanest.engineering.dto.DagPayload;
import com.datanest.engineering.dto.DsTaskDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DAG ↔ DS WorkflowDefinition 转换器
 * 决策 ADR-S3-003：DS 任务执行方式
 *   - SQL 节点 → DS HTTP 任务，回调 engineering 的内部接口
 *   - SYNC 节点 → DS HTTP 任务，回调 engineering 触发 SyncJob
 * 决策 ADR-S3-FJ：使用 fastjson2 替代 Jackson 解析 config JSON
 */
@Component
public class DagDsConverter {

    private static final Logger logger = LoggerFactory.getLogger(DagDsConverter.class);

    private final DolphinSchedulerConfig dsConfig;

    public DagDsConverter(DolphinSchedulerConfig dsConfig) {
        this.dsConfig = dsConfig;
    }

    /**
     * 把 DagNode 列表转为 DS TaskDefinition 列表
     * - SQL 节点 → DS HTTP 任务
     * - SYNC 节点 → DS HTTP 任务
     * config 解析失败抛 BusinessException
     */
    public List<DsTaskDefinition> toDsTaskDefinitions(DagPayload dag, Map<String, Long> codeMap) {
        if (dag.getNodes() == null) return Collections.emptyList();
        List<DsTaskDefinition> tasks = new ArrayList<>();
        for (DagNodePayload node : dag.getNodes()) {
            DsTaskDefinition task = new DsTaskDefinition();
            task.setCode(codeMap.get(node.getNodeId()));
            task.setName(node.getNodeName() != null ? node.getNodeName() : node.getNodeId());
            task.setDescription(null);
            task.setTaskType("HTTP");   // Sprint 3：所有节点用 HTTP 回调 engineering
            task.setWorkerGroup("default");
            task.setFlag("YES");
            task.setTimeoutFlag("CLOSE");
            task.setTimeout(0);
            task.setDelayTime(0);
            task.setFailRetryTimes(0);
            task.setFailRetryInterval(1);
            task.setEnvironmentCode(-1L);
            task.setConditionType("NONE");
            task.setResourceList(Collections.emptyList());
            task.setPreTaskCodes(collectPredecessorCodes(dag, node.getNodeId(), codeMap));

            // 构造 HTTP taskParams
            String callbackUrl = buildCallbackUrl(node, dag);
            String nodeType = node.getNodeType();
            String configJson = node.getConfig() == null ? "{}" : node.getConfig();

            // 提取 type/sqlContent/syncJobId
            String type = null, sqlContent = null, syncJobId = null, syncJobName = null;
            try {
                JSONObject cfg = JSON.parseObject(configJson);
                type = stringOrNull(cfg, "type");
                if ("SQL".equalsIgnoreCase(type)) {
                    sqlContent = stringOrNull(cfg, "sqlContent");
                } else if ("SYNC".equalsIgnoreCase(type)) {
                    syncJobId = stringOrNull(cfg, "syncJobId");
                    syncJobName = stringOrNull(cfg, "syncJobName");
                } else {
                    throw new BusinessException(ErrorCode.DS_API_ERROR,
                            "未知节点 type: " + type + " (nodeId=" + node.getNodeId() + ")");
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SQL_PARSE_FAILED,
                        "节点 config JSON 解析失败 (nodeId=" + node.getNodeId() + "): " + e.getMessage());
            }

            Map<String, Object> httpParams = new HashMap<>();
            httpParams.put("url", callbackUrl);
            httpParams.put("method", "POST");
            httpParams.put("headers", Map.of("Content-Type", "application/json"));
            Map<String, Object> body = new HashMap<>();
            body.put("nodeId", node.getNodeId());
            body.put("nodeType", nodeType);
            body.put("dagId", dag.getId());
            body.put("executionId", "${processInstanceId}");   // DS 运行时变量
            if ("SQL".equalsIgnoreCase(type)) {
                body.put("sqlContent", sqlContent);
            } else {
                Map<String, Object> syncJob = new HashMap<>();
                syncJob.put("id", syncJobId);
                syncJob.put("name", syncJobName);
                body.put("syncJob", syncJob);
            }
            httpParams.put("body", JSON.toJSONString(body));
            httpParams.put("connectTimeout", 5000);
            httpParams.put("socketTimeout", dsConfig.getCallbackTimeoutSeconds() == null
                    ? 1800000 : dsConfig.getCallbackTimeoutSeconds() * 1000);
            httpParams.put("localParams", Collections.emptyList());

            task.setTaskParams(JSON.toJSONString(httpParams));
            tasks.add(task);
        }
        return tasks;
    }

    private List<Long> collectPredecessorCodes(DagPayload dag, String nodeId, Map<String, Long> codeMap) {
        if (dag.getEdges() == null) return Collections.emptyList();
        List<Long> pre = new ArrayList<>();
        for (DagEdgePayload edge : dag.getEdges()) {
            if (nodeId.equals(edge.getTargetNodeId())) {
                Long code = codeMap.get(edge.getSourceNodeId());
                if (code != null) pre.add(code);
            }
        }
        return pre;
    }

    /**
     * 构造回调 URL
     * SQL 节点 → /engineering/dev/internal/sql/callback
     * SYNC 节点 → /engineering/dev/internal/sync/callback
     * 注意：engineering 服务 context-path=/engineering，DS worker 直连时必须带前缀
     */
    private String buildCallbackUrl(DagNodePayload node, DagPayload dag) {
        String type = node.getNodeType();
        String path = "SQL".equalsIgnoreCase(type) ? "/dev/internal/sql/callback"
                : "SYNC".equalsIgnoreCase(type) ? "/dev/internal/sync/callback"
                : "/dev/internal/unknown";
        // engineering 服务端口 8082，Docker 内服务名 app-engineering
        // 决策 ADR-S3-008：内部接口不鉴权，依赖 Docker 网络隔离
        // 路径含 context-path 前缀：/engineering + /dev/internal/...
        return "http://app-engineering:8082/engineering" + path;
    }

    private String stringOrNull(JSONObject obj, String field) {
        if (obj == null) return null;
        String v = obj.getString(field);
        return v == null || v.isEmpty() ? null : v;
    }

    /**
     * 把 nodeId 列表（前端生成）映射为 DS 数字 task code
     * Sprint 3 性能4：用 UUID 派生，避免 String.hashCode 碰撞
     * DS task code 在项目内全局唯一
     */
    public Map<String, Long> generateTaskCodes(DagPayload dag) {
        Map<String, Long> map = new HashMap<>();
        if (dag.getNodes() == null) return map;
        // dagId 来自 DagPayload.id；新建时可能为 null，用全局 UUID 兜底
        String dagKey = dag.getId() == null ? java.util.UUID.randomUUID().toString() : dag.getId().toString();
        for (DagNodePayload node : dag.getNodes()) {
            // 用 dagKey + nodeId 拼 UUID，再取前 15 位 hex 转 Long
            // 碰撞概率：每节点 1/2^60
            String uuid = java.util.UUID.nameUUIDFromBytes(
                    (dagKey + ":" + node.getNodeId()).getBytes()).toString().replace("-", "");
            long code = Long.parseUnsignedLong(uuid.substring(0, 15), 16);
            if (code == 0) code = 1L;
            map.put(node.getNodeId(), code);
        }
        return map;
    }

    /**
     * 生成 locations JSON（节点画布坐标）
     */
    public String buildLocationsJson(DagPayload dag, Map<String, Long> codeMap) {
        if (dag.getNodes() == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (DagNodePayload node : dag.getNodes()) {
            Map<String, Object> loc = new HashMap<>();
            loc.put("taskCode", codeMap.get(node.getNodeId()));
            loc.put("x", node.getPositionX() == null ? 0 : node.getPositionX());
            loc.put("y", node.getPositionY() == null ? 0 : node.getPositionY());
            list.add(loc);
        }
        return JSON.toJSONString(list);
    }

    /**
     * 生成 taskRelationJson
     */
    public String buildTaskRelationJson(DagPayload dag, Map<String, Long> codeMap) {
        if (dag.getEdges() == null || dag.getEdges().isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (DagEdgePayload edge : dag.getEdges()) {
            Map<String, Object> rel = new HashMap<>();
            rel.put("name", "");
            rel.put("preTaskCode", codeMap.get(edge.getSourceNodeId()));
            rel.put("postTaskCode", codeMap.get(edge.getTargetNodeId()));
            rel.put("conditionType", "NONE");
            rel.put("conditionParams", Collections.emptyMap());
            list.add(rel);
        }
        return JSON.toJSONString(list);
    }
}
