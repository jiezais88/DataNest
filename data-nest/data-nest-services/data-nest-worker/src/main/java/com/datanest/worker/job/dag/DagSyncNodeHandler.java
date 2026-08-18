package com.datanest.worker.job.dag;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.json.JsonUtils;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.worker.service.DagNodeExecuteService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * DAG SYNC 节点 handler（P3，替代 DS HTTP 任务 POST /dev/internal/sync/callback）。
 * syncJob{id,name} 由 DS 回调 body 改为经 EngineeringDagApi 读 dag_node.config
 * （syncJobId / syncJobName）组装，触发/互斥锁语义与原回调一致。
 */
@Component
public class DagSyncNodeHandler extends AbstractDagNodeHandler {

    public DagSyncNodeHandler(DagNodeExecuteService dagNodeExecuteService, EngineeringDagApi dagApi) {
        super(dagNodeExecuteService, dagApi);
    }

    @Override
    public String getName() {
        return "dagSyncNodeHandler";
    }

    @Override
    protected void enrichBody(Map<String, Object> body, DagNodeTask task) {
        DagNodeInfo node = fetchNode(task.dagId(), task.nodeId());
        tools.jackson.databind.node.ObjectNode config = parseNodeConfig(node, task.nodeId());
        Long syncJobId = JsonUtils.getLong(config, "syncJobId");
        if (syncJobId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SYNC 节点缺少 syncJobId: " + task.nodeId());
        }
        // 结构对齐 DS 回调 body：syncJob{id, name}
        Map<String, Object> syncJob = new HashMap<>();
        syncJob.put("id", syncJobId);
        syncJob.put("name", JsonUtils.getString(config, "syncJobName"));
        body.put("syncJob", syncJob);
    }

    @Override
    protected void doExecute(Map<String, Object> body) {
        dagNodeExecuteService.handleSyncNode(body);
    }
}
