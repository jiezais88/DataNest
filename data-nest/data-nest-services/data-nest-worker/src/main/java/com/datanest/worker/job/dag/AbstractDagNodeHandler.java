package com.datanest.worker.job.dag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.worker.job.PlatformJobHandler;
import com.datanest.worker.service.DagNodeExecuteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import tech.powerjob.common.WorkflowContextConstant;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.WorkflowContext;

import java.util.HashMap;
import java.util.Map;

/**
 * DAG 节点 handler 基类（P3：DS HTTP 回调 → PowerJob workflow 节点任务）。
 * <p>
 * 上下文契约（engineering 侧 DagPowerJobConverter 注册节点任务时写入）：
 * <ul>
 *   <li>节点身份 = JSON {@code {"dagId","nodeId","nodeType"}}：内置共享 job 形态下写 workflow 节点
 *       nodeParams，经 instanceParams 下发；instanceParams 为空时回退 jobParams（存量节点 job 兼容）</li>
 *   <li>手动触发：engineering 建 dag_execution 后 runWorkflow(initParams={"dagExecutionId":N})，
 *       initParams 经 workflowContext 透传（key = {@link WorkflowContextConstant#CONTEXT_INIT_PARAMS_KEY}，
 *       实证：powerjob-server WorkflowInstanceManager 把 initParams 放入 wfContext，
 *       worker 侧 TaskContext.getWorkflowContext().fetchWorkflowContext() 读取）</li>
 *   <li>cron 触发：initParams 无 dagExecutionId，按 wfInstanceId 经 engineering 补齐执行记录</li>
 * </ul>
 * 节点业务参数（sqlContent / syncJob 等）不再由调度器塞进任务体，handler 经
 * {@link EngineeringDagApi#getNodeByNodeId} 读 dag_node.config 自行组装，
 * 与 DS 回调 body 参数集对齐后调 {@link DagNodeExecuteService} 对应 handle* 方法。
 */
public abstract class AbstractDagNodeHandler implements PlatformJobHandler {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final DagNodeExecuteService dagNodeExecuteService;
    protected final EngineeringDagApi dagApi;

    protected AbstractDagNodeHandler(DagNodeExecuteService dagNodeExecuteService, EngineeringDagApi dagApi) {
        this.dagNodeExecuteService = dagNodeExecuteService;
        this.dagApi = dagApi;
    }

    /**
     * DAG 节点必须作为 workflow 节点触发（依赖 workflowContext），不支持纯 param 入口。
     */
    @Override
    public void execute(String param) {
        throw new UnsupportedOperationException(getName() + " 仅支持作为 PowerJob workflow 节点触发");
    }

    @Override
    public void execute(TaskContext context) {
        DagNodeTask task = parseNodeTask(context);
        Long dagExecutionId = resolveDagExecutionId(context, task.dagId());
        Map<String, Object> body = new HashMap<>();
        body.put("nodeId", task.nodeId());
        body.put("nodeType", task.nodeType());
        body.put("dagId", task.dagId());
        body.put("dagExecutionId", dagExecutionId);
        enrichBody(body, task);
        doExecute(body);
    }

    /**
     * 解析节点身份（JSON {"dagId","nodeId","nodeType"}）。
     * 优先级：先读 jobParams——PowerJob 5.1.2 工作流节点执行时，server 把节点 nodeParams
     * 塞进实例的 jobParams 位（JobNodeHandler：create(jobId, appId, node.nodeParams, wfContext, ...)），
     * 而 instanceParams 位是 wfContext（工作流 initParams，如 {"dagExecutionId":N}，不含节点身份）。
     * jobParams 解析不出 dagId 时回退 instanceParams（兜底兼容）。
     */
    protected DagNodeTask parseNodeTask(TaskContext context) {
        DagNodeTask task = tryParseNodeTask(context.getJobParams());
        if (task == null) {
            task = tryParseNodeTask(context.getInstanceParams());
        }
        if (task == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    getName() + " 节点身份参数缺失（jobParams/instanceParams 均不含 dagId/nodeId）: jobParams="
                            + context.getJobParams() + ", instanceParams=" + context.getInstanceParams());
        }
        return task;
    }

    /** 尝试按节点身份 JSON 解析，缺少 dagId/nodeId 或解析失败返回 null */
    private DagNodeTask tryParseNodeTask(String nodeParams) {
        if (!StringUtils.hasText(nodeParams)) {
            return null;
        }
        JSONObject params;
        try {
            params = JSON.parseObject(nodeParams);
        } catch (Exception e) {
            return null;
        }
        Long dagId = params.getLong("dagId");
        String nodeId = params.getString("nodeId");
        if (dagId == null || !StringUtils.hasText(nodeId)) {
            return null;
        }
        return new DagNodeTask(dagId, nodeId, params.getString("nodeType"));
    }

    /**
     * 定位 dag_execution：手动触发取 workflowContext initParams 中的 dagExecutionId；
     * cron 触发（initParams 缺失）按 wfInstanceId 经 engineering 补齐执行记录。
     */
    protected Long resolveDagExecutionId(TaskContext context, Long dagId) {
        WorkflowContext wfContext = context.getWorkflowContext();
        if (wfContext == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    getName() + " 缺少 workflowContext（非 workflow 节点任务）: dagId=" + dagId);
        }
        // PowerJob 5.1.2 server 行为（WorkflowInstanceManager:183-199）：initParams 是合法 Map JSON 时
        // 直接整体作为 wfContext（dagExecutionId 是顶层 key）；否则包一层 {"initParams": 原始串}。两种形态都兼容。
        java.util.Map<String, String> ctxMap = wfContext.fetchWorkflowContext();
        Long dagExecutionId = parseLongOrNull(ctxMap == null ? null : ctxMap.get("dagExecutionId"));
        if (dagExecutionId == null) {
            dagExecutionId = parseDagExecutionId(
                    ctxMap == null ? null : ctxMap.get(WorkflowContextConstant.CONTEXT_INIT_PARAMS_KEY));
        }
        // 嵌套子 DAG（NESTED_WORKFLOW）：子工作流继承父工作流 initParams，dagExecutionId 属于父 DAG，
        // 归属校验不通过时按本工作流实例补齐子 DAG 自己的执行记录；
        // Sprint 7 NG5：归属不匹配即嵌套场景，把父执行 ID 透传给 ensure-execution 用于主→子参数下发
        Long parentDagExecutionId = null;
        if (dagExecutionId != null) {
            if (dagNodeExecuteService.executionBelongsToDag(dagExecutionId, dagId)) {
                return dagExecutionId;
            }
            parentDagExecutionId = dagExecutionId;
        }
        Long wfInstanceId = wfContext.getWfInstanceId();
        dagExecutionId = dagNodeExecuteService.ensureExecutionByWfInstance(dagId, wfInstanceId, parentDagExecutionId);
        if (dagExecutionId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    getName() + " dag_execution 补齐失败: dagId=" + dagId + ", wfInstanceId=" + wfInstanceId);
        }
        logger.info("{} 按 wfInstanceId 补齐执行记录: dagId={}, wfInstanceId={}, dagExecutionId={}",
                getName(), dagId, wfInstanceId, dagExecutionId);
        return dagExecutionId;
    }

    /** 纯数字字符串解析，非法返回 null */
    private Long parseLongOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** initParams 兼容两种形态：JSON {"dagExecutionId":N} 或纯数字字符串 */
    private Long parseDagExecutionId(String initParams) {
        if (!StringUtils.hasText(initParams)) {
            return null;
        }
        try {
            Long id = JSON.parseObject(initParams).getLong("dagExecutionId");
            if (id != null) {
                return id;
            }
        } catch (Exception e) {
            // 非 JSON，按纯数字字符串兜底解析
        }
        try {
            return Long.parseLong(initParams.trim());
        } catch (Exception e) {
            logger.warn("{} initParams 中无 dagExecutionId: {}", getName(), initParams);
            return null;
        }
    }

    /** 经 Feign 读节点定义（config JSON 由子类解析），降级返回 null */
    protected DagNodeInfo fetchNode(Long dagId, String nodeId) {
        return RemoteCalls.execute("engineering.dag.node-by-node-id", () -> {
            Result<DagNodeInfo> result = dagApi.getNodeByNodeId(dagId, nodeId);
            return result == null ? null : result.data();
        }, null);
    }

    /** 解析节点 config JSON，缺失/解析失败抛 BusinessException */
    protected JSONObject parseNodeConfig(DagNodeInfo node, String nodeId) {
        if (node == null || !StringUtils.hasText(node.getConfig())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "节点配置缺失: " + nodeId);
        }
        try {
            return JSON.parseObject(node.getConfig());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "节点 config JSON 解析失败 (nodeId=" + nodeId + "): " + e.getMessage(), e);
        }
    }

    /** 子类按需补充类型特定参数（SQL 的 sqlContent、SYNC 的 syncJob），默认无 */
    protected void enrichBody(Map<String, Object> body, DagNodeTask task) {
    }

    /** 调 {@link DagNodeExecuteService} 对应 handle* 方法 */
    protected abstract void doExecute(Map<String, Object> body);

    /** 节点任务坐标（来自 instanceParams，空则回退 jobParams） */
    protected record DagNodeTask(Long dagId, String nodeId, String nodeType) {
    }
}
