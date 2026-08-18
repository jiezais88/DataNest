package com.datanest.engineering.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.json.JsonUtils;
import com.datanest.common.scheduler.PJDag;
import com.datanest.common.scheduler.PJEdge;
import com.datanest.common.scheduler.PJNode;
import com.datanest.engineering.entity.Dag;
import com.datanest.engineering.entity.DagEdge;
import com.datanest.engineering.entity.DagNode;
import com.datanest.engineering.mapper.DagMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DAG → PowerJob Workflow 转换器（P3：替换 DagDsConverter，后者已随 P4 旧列清理删除）。
 * <p>
 * 节点映射约定（5 内置共享 job 重构后）：
 * <ul>
 *   <li>SQL / SYNC / PYTHON / CONDITION / SUB_DAG(异步) → PowerJob JOB 节点（nodeType=1），
 *       jobId 取 5 个内置共享 job（按节点类型映射 {@link #BUILTIN_JOB_NAME_BY_TYPE}，worker 启动时注册），
 *       节点身份 JSON {"dagId":..,"nodeId":..,"nodeType":..} 写 workflow 节点 nodeParams，
 *       worker processor 经 instanceParams 读取</li>
 *   <li>SUB_DAG(同步) → NESTED_WORKFLOW 节点（nodeType=3），jobId = 子 DAG 的 powerjobWorkflowId，无内置 job</li>
 * </ul>
 * PJDag 的 Node.nodeId 用 dag_node.powerjob_node_id（server 侧 workflow_node_info 记录 ID，
 * 注册流经 saveWorkflowNode 注册并回写），Edge from/to 同理。
 */
@Component
public class DagPowerJobConverter {

    /** worker 侧 DAG 节点 handler 名（内置 job 的 processorInfo 约定） */
    public static final String HANDLER_SQL = "dagSqlNodeHandler";
    public static final String HANDLER_SYNC = "dagSyncNodeHandler";
    public static final String HANDLER_PYTHON = "dagPythonNodeHandler";
    public static final String HANDLER_CONDITION = "dagConditionNodeHandler";
    public static final String HANDLER_SUB_DAG_ASYNC = "dagSubDagAsyncHandler";

    /** 内置共享 job 固定名称（worker 启动时经 ensureBuiltinNodeJob 幂等注册） */
    public static final String BUILTIN_JOB_NAME_SQL = "内置-DAG-SQL节点";
    public static final String BUILTIN_JOB_NAME_SYNC = "内置-DAG-SYNC节点";
    public static final String BUILTIN_JOB_NAME_PYTHON = "内置-DAG-PYTHON节点";
    public static final String BUILTIN_JOB_NAME_CONDITION = "内置-DAG-CONDITION节点";
    public static final String BUILTIN_JOB_NAME_SUB_DAG_ASYNC = "内置-DAG-子DAG异步节点";

    /** 节点类型 → 内置共享 job 固定名称（SUB_DAG 指异步执行；同步子 DAG 走 NESTED_WORKFLOW 不在此列） */
    public static final Map<String, String> BUILTIN_JOB_NAME_BY_TYPE = Map.of(
            "SQL", BUILTIN_JOB_NAME_SQL,
            "SYNC", BUILTIN_JOB_NAME_SYNC,
            "PYTHON", BUILTIN_JOB_NAME_PYTHON,
            "CONDITION", BUILTIN_JOB_NAME_CONDITION,
            "SUB_DAG", BUILTIN_JOB_NAME_SUB_DAG_ASYNC);

    /** PowerJob WorkflowNodeType.JOB */
    public static final int PJ_NODE_TYPE_JOB = 1;
    /** PowerJob WorkflowNodeType.NESTED_WORKFLOW */
    public static final int PJ_NODE_TYPE_NESTED_WORKFLOW = 3;

    private final DagMapper dagMapper;

    public DagPowerJobConverter(DagMapper dagMapper) {
        this.dagMapper = dagMapper;
    }

    /**
     * 节点注册计划：一个 dag_node 对应一条。
     *
     * @param dagNodeId      dag_node 主键（注册结果按它回写）
     * @param nodeUuid       前端生成的节点 UUID（dag_node.node_id，节点身份 JSON 与 node_execution 用它）
     * @param nodeName       节点名称（saveWorkflowNode 的节点别名）
     * @param nodeType       节点类型（SQL/SYNC/PYTHON/CONDITION/SUB_DAG）
     * @param nestedWorkflow true = 同步子 DAG（NESTED_WORKFLOW 节点，jobId 为子 DAG 工作流 ID）
     * @param powerjobNodeId dag_node 已持久化的 workflow_node_info 节点 ID（有值则 saveWorkflowNode 带 id 更新，无则新建）
     * @param resolvedJobId  嵌套工作流节点已解析的 jobId（子 DAG 的 powerjobWorkflowId）；普通 JOB 节点为 null，
     *                       注册时按节点类型取内置共享 jobId
     * @param nodeParams     节点身份 JSON {"dagId":..,"nodeId":..,"nodeType":..}（写 workflow 节点 nodeParams；
     *                       嵌套工作流节点为 null）
     */
    public record NodeJobDef(Long dagNodeId, String nodeUuid, String nodeName, String nodeType,
                             boolean nestedWorkflow, Long powerjobNodeId,
                             Long resolvedJobId, String nodeParams) {
    }

    /**
     * 把 dag_node 列表转为节点注册计划。
     * 同步子 DAG 节点在此完成子 DAG 引用校验并解析其 powerjobWorkflowId
     * （对齐 DagDsConverter.buildSubWorkflowTask 的校验语义）。
     */
    public List<NodeJobDef> toNodeJobDefs(Long dagId, List<DagNode> nodes) {
        List<NodeJobDef> defs = new ArrayList<>(nodes == null ? 0 : nodes.size());
        if (nodes == null) {
            return defs;
        }
        for (DagNode node : nodes) {
            String configJson = node.getConfig() == null ? "{}" : node.getConfig();
            tools.jackson.databind.node.ObjectNode cfg;
            try {
                cfg = JsonUtils.parseObject(configJson);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SQL_PARSE_FAILED,
                        "节点 config JSON 解析失败 (nodeId=" + node.getNodeId() + "): " + e.getMessage());
            }
            // 节点类型优先取 config.type（与 DagDsConverter 一致），兜底 node_type 列
            String type = cfg == null ? null : JsonUtils.getString(cfg, "type");
            if (!StringUtils.hasText(type)) {
                type = node.getNodeType();
            }
            if (!StringUtils.hasText(type)) {
                throw new BusinessException(ErrorCode.SCHEDULER_API_ERROR,
                        "节点缺少 type (nodeId=" + node.getNodeId() + ")");
            }
            type = type.toUpperCase();

            switch (type) {
                case "SQL", "SYNC", "PYTHON", "CONDITION" -> defs.add(jobDef(dagId, node, type));
                case "SUB_DAG" -> {
                    Long subDagId = JsonUtils.getLong(cfg, "subDagId");
                    if (subDagId == null) {
                        throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND,
                                "子 DAG 节点缺少 subDagId (nodeId=" + node.getNodeId() + ")");
                    }
                    Boolean syncExecutionVal = JsonUtils.getBoolean(cfg, "syncExecution");
                    boolean syncExecution = syncExecutionVal == null || syncExecutionVal;
                    if (syncExecution) {
                        // 同步子 DAG：NESTED_WORKFLOW 节点，jobId = 子 DAG 的 powerjobWorkflowId
                        Dag subDag = dagMapper.selectById(subDagId);
                        if (subDag == null) {
                            throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND,
                                    "子 DAG 不存在: subDagId=" + subDagId);
                        }
                        if (!"ENABLED".equalsIgnoreCase(subDag.getStatus())) {
                            throw new BusinessException(ErrorCode.SUB_DAG_DISABLED, "子 DAG 未启用: " + subDag.getName());
                        }
                        if (subDag.getPowerjobWorkflowId() == null) {
                            throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND,
                                    "子 DAG 尚未注册到 PowerJob，请先保存一次子 DAG: " + subDag.getName());
                        }
                        defs.add(new NodeJobDef(node.getId(), node.getNodeId(), node.getNodeName(), type, true,
                                node.getPowerjobNodeId(), subDag.getPowerjobWorkflowId(), null));
                    } else {
                        // 异步子 DAG：普通 JOB 节点，worker 的 dagSubDagAsyncHandler 回调 engineering 触发子 DAG
                        defs.add(jobDef(dagId, node, type));
                    }
                }
                default -> throw new BusinessException(ErrorCode.SCHEDULER_API_ERROR,
                        "未知节点 type: " + type + " (nodeId=" + node.getNodeId() + ")");
            }
        }
        return defs;
    }

    /**
     * 装配 PJDag（PowerJob PEWorkflowDAG 点线表示法）：Node.nodeId 取 dag_node.powerjob_node_id
     * （调用前须已完成节点注册并回写到 nodes 内存实体），jobId 嵌套节点取子 DAG workflowId、
     * 普通 JOB 节点按节点类型取内置共享 jobId；Edge from/to 取上下游 powerjob_node_id。
     */
    public PJDag buildWorkflowDag(List<DagNode> nodes, List<DagEdge> edges, List<NodeJobDef> defs,
                                  Map<String, Long> builtinJobIdByType) {
        Map<String, NodeJobDef> defByUuid = defs.stream()
                .collect(Collectors.toMap(NodeJobDef::nodeUuid, Function.identity(), (a, b) -> a));
        Map<String, Long> pjNodeIdByUuid = new HashMap<>();
        List<PJNode> pjNodes = new ArrayList<>(nodes.size());
        for (DagNode node : nodes) {
            pjNodeIdByUuid.put(node.getNodeId(), node.getPowerjobNodeId());
            NodeJobDef def = defByUuid.get(node.getNodeId());
            boolean nested = def != null && def.nestedWorkflow();
            Long jobId = nested ? def.resolvedJobId()
                    : def == null ? null : builtinJobIdByType.get(def.nodeType());
            pjNodes.add(new PJNode(node.getPowerjobNodeId(), node.getNodeName(),
                    nested ? PJ_NODE_TYPE_NESTED_WORKFLOW : PJ_NODE_TYPE_JOB, jobId, null, null));
        }
        List<PJEdge> pjEdges = new ArrayList<>();
        if (edges != null) {
            for (DagEdge edge : edges) {
                Long from = pjNodeIdByUuid.get(edge.getSourceNodeId());
                Long to = pjNodeIdByUuid.get(edge.getTargetNodeId());
                if (from != null && to != null) {
                    pjEdges.add(new PJEdge(from, to));
                }
            }
        }
        return new PJDag(pjNodes, pjEdges);
    }

    private NodeJobDef jobDef(Long dagId, DagNode node, String type) {
        return new NodeJobDef(node.getId(), node.getNodeId(), node.getNodeName(), type, false,
                node.getPowerjobNodeId(), null, buildNodeIdentityParams(dagId, node, type));
    }

    /** 节点身份 JSON：{"dagId":..,"nodeId":..,"nodeType":..}（写 workflow 节点 nodeParams，worker 经 instanceParams 解析并回查节点配置） */
    private String buildNodeIdentityParams(Long dagId, DagNode node, String type) {
        Map<String, Object> params = new HashMap<>();
        params.put("dagId", dagId);
        params.put("nodeId", node.getNodeId());
        params.put("nodeType", type);
        return JsonUtils.toJSONString(params);
    }
}
