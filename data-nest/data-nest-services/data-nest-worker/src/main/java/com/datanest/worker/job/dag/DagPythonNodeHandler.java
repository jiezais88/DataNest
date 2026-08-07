package com.datanest.worker.job.dag;

import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.worker.service.DagNodeExecuteService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DAG PYTHON 节点 handler（P3，替代 DS HTTP 任务 POST /dev/internal/python/callback）。
 * 脚本/超时等配置本就由 handlePythonNode 自行读 dag_node.config，无需额外组装参数。
 */
@Component
public class DagPythonNodeHandler extends AbstractDagNodeHandler {

    public DagPythonNodeHandler(DagNodeExecuteService dagNodeExecuteService, EngineeringDagApi dagApi) {
        super(dagNodeExecuteService, dagApi);
    }

    @Override
    public String getName() {
        return "dagPythonNodeHandler";
    }

    @Override
    protected void doExecute(Map<String, Object> body) {
        dagNodeExecuteService.handlePythonNode(body);
    }
}
