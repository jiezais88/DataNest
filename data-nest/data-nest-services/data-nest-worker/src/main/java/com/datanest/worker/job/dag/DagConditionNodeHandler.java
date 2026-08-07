package com.datanest.worker.job.dag;

import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.worker.service.DagNodeExecuteService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DAG CONDITION 节点 handler（P3，替代 DS HTTP 任务 POST /dev/internal/condition/callback）。
 * 分支求值/SKIPPED 标记语义原样保留在 DagNodeExecuteService.handleConditionNode。
 */
@Component
public class DagConditionNodeHandler extends AbstractDagNodeHandler {

    public DagConditionNodeHandler(DagNodeExecuteService dagNodeExecuteService, EngineeringDagApi dagApi) {
        super(dagNodeExecuteService, dagApi);
    }

    @Override
    public String getName() {
        return "dagConditionNodeHandler";
    }

    @Override
    protected void doExecute(Map<String, Object> body) {
        dagNodeExecuteService.handleConditionNode(body);
    }
}
