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
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.mapper.DagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * DAG ↔ DS WorkflowDefinition 转换器
 * 决策 ADR-S3-003：DS 任务执行方式
 *   - SQL 节点 → DS HTTP 任务，回调 engineering 的内部接口
 *   - SYNC 节点 → DS HTTP 任务，回调 engineering 触发 SyncJob
 * 决策 ADR-S3-FJ：使用 fastjson2 替代 Jackson 解析 config JSON
 * Sprint 5：
 *   - CONDITION 节点 → DS HTTP 任务，回调 worker 求值分支（worker 全量求值 + 分支 gate）
 *   - SUB_DAG 同步 → DS SUB_WORKFLOW 任务（原生等待子流程完成）
 *   - SUB_DAG 异步 → DS HTTP 任务，回调 engineering 触发子 DAG
 */
@Component
public class DagDsConverter {

    private static final Logger logger = LoggerFactory.getLogger(DagDsConverter.class);

    private final DolphinSchedulerConfig dsConfig;
    private final DagMapper dagMapper;

    public DagDsConverter(DolphinSchedulerConfig dsConfig, DagMapper dagMapper) {
        this.dsConfig = dsConfig;
        this.dagMapper = dagMapper;
    }

    /**
     * 把 DagNode 列表转为 DS TaskDefinition 列表
     * - SQL 节点 → DS HTTP 任务
     * - SYNC 节点 → DS HTTP 任务
     * - PYTHON 节点 → DS HTTP 任务
     * - CONDITION 节点 → DS HTTP 任务（worker 求值分支）
     * - SUB_DAG 同步 → DS SUB_WORKFLOW 任务；异步 → DS HTTP 任务触发 engineering
     * config 解析失败抛 BusinessException
     */
    public List<DsTaskDefinition> toDsTaskDefinitions(DagPayload dag, Map<String, Long> codeMap) {
        if (dag.getNodes() == null) return Collections.emptyList();
        List<DsTaskDefinition> tasks = new ArrayList<>();
        for (DagNodePayload node : dag.getNodes()) {
            DsTaskDefinition task = new DsTaskDefinition();
            task.setCode(codeMap.get(node.getNodeId()));
            // DS 工作流内 task name 必须唯一；默认节点名（如“同步任务”）重复时会导致后一个覆盖前一个，
            // 所以用“节点名_节点ID后8位”保证唯一性，同时保留可读性。
            task.setName(buildDsTaskName(node));
            task.setDescription(null);
            task.setWorkerGroup("default");
            task.setFlag("YES");
            task.setDelayTime(0);
            task.setFailRetryTimes(0);
            task.setFailRetryInterval(1);
            task.setEnvironmentCode(-1L);
            task.setConditionType("NONE");
            task.setResourceList(Collections.emptyList());

            String configJson = node.getConfig() == null ? "{}" : node.getConfig();
            JSONObject cfg;
            String type;
            try {
                cfg = JSON.parseObject(configJson);
                type = cfg == null ? null : cfg.getString("type");
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SQL_PARSE_FAILED,
                        "节点 config JSON 解析失败 (nodeId=" + node.getNodeId() + "): " + e.getMessage());
            }
            if (type == null || type.isBlank()) {
                throw new BusinessException(ErrorCode.DS_API_ERROR,
                        "节点缺少 type (nodeId=" + node.getNodeId() + ")");
            }

            switch (type.toUpperCase()) {
                case "SQL" -> {
                    String sqlContent = stringOrNull(cfg, "sqlContent");
                    task.setTaskType("HTTP");
                    Map<String, Object> requestBody = commonBody(node, dag);
                    requestBody.put("sqlContent", sqlContent);
                    buildHttpTask(task, buildCallbackUrl(node, dag), requestBody);
                }
                case "SYNC" -> {
                    String syncJobId = stringOrNull(cfg, "syncJobId");
                    String syncJobName = stringOrNull(cfg, "syncJobName");
                    task.setTaskType("HTTP");
                    Map<String, Object> requestBody = commonBody(node, dag);
                    Map<String, Object> syncJob = new HashMap<>();
                    syncJob.put("id", syncJobId);
                    syncJob.put("name", syncJobName);
                    requestBody.put("syncJob", syncJob);
                    buildHttpTask(task, buildCallbackUrl(node, dag), requestBody);
                }
                case "PYTHON" -> {
                    String pythonScript = stringOrNull(cfg, "pythonScript");
                    Integer timeoutMinutes = cfg.getInteger("timeoutMinutes");
                    // PYTHON 节点按配置的超时时间启用 DS 任务级超时
                    task.setTimeoutFlag("OPEN");
                    int tm = timeoutMinutes == null || timeoutMinutes <= 0 ? 30 : timeoutMinutes;
                    task.setTimeout(tm * 60);
                    task.setTaskType("HTTP");
                    Map<String, Object> requestBody = commonBody(node, dag);
                    requestBody.put("pythonScript", pythonScript);
                    buildHttpTask(task, buildCallbackUrl(node, dag), requestBody);
                }
                case "CONDITION" -> {
                    // worker 从 dag_node.config 读分支配置求值，DS 侧只需按普通 HTTP 任务回调
                    task.setTaskType("HTTP");
                    buildHttpTask(task, buildCallbackUrl(node, dag), commonBody(node, dag));
                }
                case "SUB_DAG" -> {
                    Long subDagId = cfg.getLong("subDagId");
                    boolean syncExecution = cfg.getBooleanValue("syncExecution", true);
                    if (subDagId == null) {
                        throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND,
                                "子 DAG 节点缺少 subDagId (nodeId=" + node.getNodeId() + ")");
                    }
                    if (syncExecution) {
                        buildSubWorkflowTask(task, subDagId);
                    } else {
                        // 异步：HTTP 触发 engineering 内部端点，立即返回，不等待子 DAG
                        task.setTaskType("HTTP");
                        Map<String, Object> requestBody = commonBody(node, dag);
                        requestBody.put("subDagId", subDagId);
                        requestBody.put("subDagName", cfg.getString("subDagName"));
                        buildHttpTask(task, dsConfig.getEngineeringCallbackBaseUrl() + "/dev/internal/subdag/trigger",
                                requestBody);
                    }
                }
                default -> throw new BusinessException(ErrorCode.DS_API_ERROR,
                        "未知节点 type: " + type + " (nodeId=" + node.getNodeId() + ")");
            }
            tasks.add(task);
        }
        return tasks;
    }

    private Map<String, Object> commonBody(DagNodePayload node, DagPayload dag) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("nodeId", node.getNodeId());
        requestBody.put("nodeType", node.getNodeType());
        requestBody.put("dagId", dag.getId());
        requestBody.put("executionId", "${system.workflow.instance.id}");   // DS 内置变量：工作流实例 ID
        return requestBody;
    }

    /**
     * 子 DAG 同步执行：映射为 DS SUB_WORKFLOW 逻辑任务。
     * DS 原生触发子工作流并等待完成，父节点状态跟随子流程（SUCCESS/FAILURE）。
     */
    private void buildSubWorkflowTask(DsTaskDefinition task, Long subDagId) {
        Dag subDag = dagMapper.selectById(subDagId);
        if (subDag == null || subDag.getDsProcessDefinitionCode() == null) {
            throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND,
                    "子 DAG 不存在或未同步到 DS: subDagId=" + subDagId);
        }
        if (!"ENABLED".equalsIgnoreCase(subDag.getStatus())) {
            throw new BusinessException(ErrorCode.SUB_DAG_DISABLED, "子 DAG 未启用: " + subDag.getName());
        }
        task.setTaskType("SUB_WORKFLOW");
        // SubWorkflowParameters：{"workflowDefinitionCode": N}
        task.setTaskParams("{\"workflowDefinitionCode\":" + subDag.getDsProcessDefinitionCode() + "}");
        task.setTimeoutFlag("CLOSE");
        task.setTimeout(0);
    }

    /**
     * 填充 DS 3.4.2 HTTP 任务参数（HttpParameters 平铺结构）。
     */
    private void buildHttpTask(DsTaskDefinition task, String url, Map<String, Object> requestBody) {
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
        taskParams.put("url", url);
        taskParams.put("httpMethod", "POST");
        taskParams.put("httpBody", JSON.toJSONString(requestBody));
        taskParams.put("httpCheckCondition", "STATUS_CODE_DEFAULT");
        taskParams.put("condition", "");
        taskParams.put("connectTimeout", "5000");

        task.setTaskParams(JSON.toJSONString(taskParams));
    }

    /**
     * 构造回调 URL
     * SQL 节点 → {callbackBaseUrl}/dev/internal/sql/callback
     * SYNC 节点 → {callbackBaseUrl}/dev/internal/sync/callback
     * PYTHON 节点 → {callbackBaseUrl}/dev/internal/python/callback
     * CONDITION 节点 → {callbackBaseUrl}/dev/internal/condition/callback
     * 决策 ADR-S3-012：回调走 gateway（默认 {@code http://app-gateway:8080/api/engineering}），
     * gateway 路由 StripPrefix=1 → engineering 收到 {@code /dev/internal/...}。
     * 决策 ADR-S3-008：内部接口不鉴权，依赖 Docker 网络隔离 + gateway 白名单。
     */
    private String buildCallbackUrl(DagNodePayload node, DagPayload dag) {
        String type = node.getNodeType();
        String path = switch (type.toUpperCase()) {
            case "SQL" -> "/dev/internal/sql/callback";
            case "SYNC" -> "/dev/internal/sync/callback";
            case "PYTHON" -> "/dev/internal/python/callback";
            case "CONDITION" -> "/dev/internal/condition/callback";
            default -> "/dev/internal/unknown";
        };
        // 不带尾斜杠：base url 默认已含 /api/engineering，path 前缀 /dev/...
        return dsConfig.getCallbackBaseUrl() + path;
    }

    private String stringOrNull(JSONObject obj, String field) {
        if (obj == null) return null;
        String v = obj.getString(field);
        return v == null || v.isEmpty() ? null : v;
    }

    private String buildDsTaskName(DagNodePayload node) {
        return buildDsTaskName(node.getNodeName(), node.getNodeId(), node.getNodeType());
    }

    /**
     * 生成 DS 任务名称（项目内唯一）。
     * 公开静态方法供重跑失败节点时反查 startNodeList 使用。
     */
    public static String buildDsTaskName(String nodeName, String nodeId, String nodeType) {
        String base = StringUtils.hasText(nodeName) ? nodeName : nodeId;
        String id = nodeId == null ? "" : nodeId;
        String suffix = id.length() > 8 ? id.substring(id.length() - 8) : id;
        if (base == null) {
            base = nodeType;
        }
        return base + "_" + suffix;
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
