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

            // DS 3.4.2 HTTP 任务参数类：org.apache.dolphinscheduler.plugin.task.http.HttpParameters
            // 字段必须严格匹配，否则 checkParameters() 失败
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("nodeId", node.getNodeId());
            requestBody.put("nodeType", nodeType);
            requestBody.put("dagId", dag.getId());
            requestBody.put("executionId", "${system.workflow.instance.id}");   // DS 内置变量：工作流实例 ID
            if ("SQL".equalsIgnoreCase(type)) {
                requestBody.put("sqlContent", sqlContent);
            } else {
                Map<String, Object> syncJob = new HashMap<>();
                syncJob.put("id", syncJobId);
                syncJob.put("name", syncJobName);
                requestBody.put("syncJob", syncJob);
            }

            // httpParams 是 List<HttpProperty>，字段：prop / httpParametersType / value
            Map<String, Object> contentTypeProp = new HashMap<>();
            contentTypeProp.put("prop", "Content-Type");
            contentTypeProp.put("httpParametersType", "HEADERS");
            contentTypeProp.put("value", "application/json");

            // DS 3.4.2 HTTP taskParams 是平铺的 HttpParameters 对象：
            // {"localParams":[],"httpParams":[],"url":"...","httpMethod":"POST",
            //  "httpBody":"...","httpCheckCondition":"...","condition":"","connectTimeout":"5000"}
            Map<String, Object> taskParams = new HashMap<>();
            taskParams.put("localParams", Collections.emptyList());
            taskParams.put("httpParams", java.util.List.of(contentTypeProp));
            taskParams.put("url", callbackUrl);
            taskParams.put("httpMethod", "POST");
            taskParams.put("httpBody", JSON.toJSONString(requestBody));
            taskParams.put("httpCheckCondition", "STATUS_CODE_DEFAULT");
            taskParams.put("condition", "");
            taskParams.put("connectTimeout", "5000");

            task.setTaskParams(JSON.toJSONString(taskParams));
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * 构造回调 URL
     * SQL 节点 → {callbackBaseUrl}/dev/internal/sql/callback
     * SYNC 节点 → {callbackBaseUrl}/dev/internal/sync/callback
     * 决策 ADR-S3-012：回调走 gateway（默认 {@code http://app-gateway:8080/api/engineering}），
     * gateway 路由 StripPrefix=1 → engineering 收到 {@code /dev/internal/...}。
     * 决策 ADR-S3-008：内部接口不鉴权，依赖 Docker 网络隔离 + gateway 白名单。
     */
    private String buildCallbackUrl(DagNodePayload node, DagPayload dag) {
        String type = node.getNodeType();
        String path = "SQL".equalsIgnoreCase(type) ? "/dev/internal/sql/callback"
                : "SYNC".equalsIgnoreCase(type) ? "/dev/internal/sync/callback"
                : "/dev/internal/unknown";
        // 不带尾斜杠：base url 默认已含 /api/engineering，path 前缀 /dev/...
        return dsConfig.getCallbackBaseUrl() + path;
    }

    private String stringOrNull(JSONObject obj, String field) {
        if (obj == null) return null;
        String v = obj.getString(field);
        return v == null || v.isEmpty() ? null : v;
    }

    /**
     * 把 nodeId 列表（前端生成）映射为 DS 数字 task code。
     * Sprint 3 性能4：用 UUID 派生，避免 String.hashCode 碰撞。
     * Sprint 3 P2：优先复用已有 ds_task_code（节点重命名后保持不变）。
     * DS task code 在项目内全局唯一。
     */
    public Map<String, Long> generateTaskCodes(DagPayload dag, Map<String, Long> existingCodeMap) {
        Map<String, Long> map = new HashMap<>();
        if (dag.getNodes() == null) return map;
        // dagId 来自 DagPayload.id；新建时可能为 null，用全局 UUID 兜底
        String dagKey = dag.getId() == null ? java.util.UUID.randomUUID().toString() : dag.getId().toString();
        for (DagNodePayload node : dag.getNodes()) {
            // 优先复用已有 code
            Long existing = existingCodeMap != null ? existingCodeMap.get(node.getNodeId()) : null;
            if (existing != null) {
                map.put(node.getNodeId(), existing);
                continue;
            }
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
     * DS 3.4.2 要求每个节点都有一条入站关系：
     * - 无上游的节点：preTaskCode=0，postTaskCode=节点 code
     * - 有上游的节点：preTaskCode=上游 code，postTaskCode=节点 code
     * 多前驱时，每个上游单独一条关系，DS 的 ProcessService.transformTask 会合并。
     */
    public String buildTaskRelationJson(DagPayload dag, Map<String, Long> codeMap) {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> nodesWithPredecessor = new HashSet<>();

        // 1. 生成所有边关系（保留多前驱）
        if (dag.getEdges() != null) {
            for (DagEdgePayload edge : dag.getEdges()) {
                Long preCode = codeMap.get(edge.getSourceNodeId());
                Long postCode = codeMap.get(edge.getTargetNodeId());
                if (preCode != null && postCode != null) {
                    list.add(buildRelation(preCode, postCode));
                    nodesWithPredecessor.add(edge.getTargetNodeId());
                }
            }
        }

        // 2. 给无上游的节点生成 pre=0 的入口关系
        if (dag.getNodes() != null) {
            for (DagNodePayload node : dag.getNodes()) {
                if (!nodesWithPredecessor.contains(node.getNodeId())) {
                    Long postCode = codeMap.get(node.getNodeId());
                    if (postCode != null) {
                        list.add(buildRelation(0L, postCode));
                    }
                }
            }
        }

        return list.isEmpty() ? "[]" : JSON.toJSONString(list);
    }

    private Map<String, Object> buildRelation(Long preTaskCode, Long postTaskCode) {
        Map<String, Object> rel = new HashMap<>();
        rel.put("name", "");
        rel.put("preTaskCode", preTaskCode == null ? 0L : preTaskCode);
        rel.put("preTaskVersion", 1);
        rel.put("postTaskCode", postTaskCode == null ? 0L : postTaskCode);
        rel.put("postTaskVersion", 1);
        rel.put("conditionType", "NONE");
        rel.put("conditionParams", "{}");
        return rel;
    }
}
