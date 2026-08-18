package com.datanest.worker.job.dag;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.json.JsonUtils;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.worker.service.DagNodeExecuteService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * DAG SQL 节点 handler（P3，替代 DS HTTP 任务 POST /dev/internal/sql/callback）。
 * sqlContent 由 DS 回调 body 改为经 EngineeringDagApi 读 dag_node.config 获取，
 * 其余执行语义（条件分支 gate / 状态机 / 日志 / 血缘）与原回调一致。
 */
@Component
public class DagSqlNodeHandler extends AbstractDagNodeHandler {

    public DagSqlNodeHandler(DagNodeExecuteService dagNodeExecuteService, EngineeringDagApi dagApi) {
        super(dagNodeExecuteService, dagApi);
    }

    @Override
    public String getName() {
        return "dagSqlNodeHandler";
    }

    @Override
    protected void enrichBody(Map<String, Object> body, DagNodeTask task) {
        DagNodeInfo node = fetchNode(task.dagId(), task.nodeId());
        tools.jackson.databind.node.ObjectNode config = parseNodeConfig(node, task.nodeId());
        String sqlContent = JsonUtils.getString(config, "sqlContent");
        if (!StringUtils.hasText(sqlContent)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SQL 节点 sqlContent 为空: " + task.nodeId());
        }
        body.put("sqlContent", sqlContent);
    }

    @Override
    protected void doExecute(Map<String, Object> body) {
        dagNodeExecuteService.handleSqlNode(body);
    }
}
