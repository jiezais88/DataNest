package com.datanest.worker.job.dag;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.json.JsonUtils;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.engineering.api.EngineeringSubDagApi;
import com.datanest.worker.service.DagNodeExecuteService;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.TaskContext;

import java.util.HashMap;
import java.util.Map;

/**
 * DAG 子 DAG 异步触发节点 handler（P3，替代 DS HTTP 任务回调 engineering /dev/internal/subdag/trigger）。
 * 语义与原回调一致：触发子 DAG 独立执行后立即返回成功，不等待子 DAG 完成、不回写本节点
 * node_execution（节点终态由 DagExecutionSyncService 按 workflow 节点状态同步）。
 * <p>
 * 与 4 个执行类节点不同：不需要 dagExecutionId（触发端点不消费），故覆盖 execute(TaskContext)
 * 跳过基类的执行记录定位。
 */
@Component
public class DagSubDagAsyncHandler extends AbstractDagNodeHandler {

    private final EngineeringSubDagApi subDagApi;

    public DagSubDagAsyncHandler(DagNodeExecuteService dagNodeExecuteService,
                                 EngineeringDagApi dagApi,
                                 EngineeringSubDagApi subDagApi) {
        super(dagNodeExecuteService, dagApi);
        this.subDagApi = subDagApi;
    }

    @Override
    public String getName() {
        return "dagSubDagAsyncHandler";
    }

    @Override
    public void execute(TaskContext context) {
        DagNodeTask task = parseNodeTask(context);
        DagNodeInfo node = fetchNode(task.dagId(), task.nodeId());
        tools.jackson.databind.node.ObjectNode config = parseNodeConfig(node, task.nodeId());
        Long subDagId = JsonUtils.getLong(config, "subDagId");
        if (subDagId == null) {
            throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND, "子 DAG 节点缺少 subDagId: " + task.nodeId());
        }
        // body 对齐 SubDagTriggerController 契约：{ dagId, nodeId, subDagId, subDagName }
        Map<String, Object> body = new HashMap<>();
        body.put("dagId", task.dagId());
        body.put("nodeId", task.nodeId());
        body.put("subDagId", subDagId);
        body.put("subDagName", JsonUtils.getString(config, "subDagName"));

        logger.info("子 DAG 异步触发: subDagId={}, parentDagId={}, parentNodeId={}",
                subDagId, task.dagId(), task.nodeId());
        Result<Map<String, Object>> result = subDagApi.triggerSubDag(body);
        // 无 fallback 的 Feign 调用失败会直接抛异常（节点标失败）；兜底校验业务码
        if (result == null || result.code() != 200) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "子 DAG 触发失败: subDagId=" + subDagId
                            + (result == null ? "（engineering 无响应）" : ": " + result.message()));
        }
    }

    @Override
    protected void doExecute(Map<String, Object> body) {
        // 本 handler 不经 DagNodeExecuteService，逻辑在 execute(TaskContext) 内
        throw new UnsupportedOperationException(getName() + " 不支持 doExecute 入口");
    }
}
