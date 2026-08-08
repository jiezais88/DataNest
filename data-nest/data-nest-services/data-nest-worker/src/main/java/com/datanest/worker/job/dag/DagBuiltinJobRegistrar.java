package com.datanest.worker.job.dag;

import com.datanest.common.scheduler.PowerJobWorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时对 5 个内置共享 DAG 节点 job 做幂等 ensure（ensure 风格参考 job 服务 scheduler/JobRegistrar）。
 * <p>
 * 注册模式：不再「每节点一个 job」，而是每类节点一个内置共享 job（JOB 级不带节点身份），
 * 节点身份 JSON {"dagId","nodeId","nodeType"} 由 engineering 侧写 workflow 节点 nodeParams，
 * PowerJob 节点 nodeParams 非空时覆盖 jobParams 作为实例参数下发，
 * processor 侧经 {@code TaskContext.getInstanceParams()} 读取（见 {@link AbstractDagNodeHandler#parseNodeTask}）。
 * 单个 ensure 失败只 warn 不阻塞启动（server 未就绪场景，下次重启自愈）。
 */
@Component
public class DagBuiltinJobRegistrar implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DagBuiltinJobRegistrar.class);

    /** PowerJob App 名（与 powerjob.worker.app-name 一致，App 已在 server 预置，id=2） */
    private static final String APP_NAME = "data-nest-worker";

    /** 内置 job 固定名称映射：jobName → processorInfo（handler Bean 名，PlatformJobHandler 路由键） */
    private static final Map<String, String> BUILTIN_JOBS = new LinkedHashMap<>();

    static {
        BUILTIN_JOBS.put("内置-DAG-SQL节点", "dagSqlNodeHandler");
        BUILTIN_JOBS.put("内置-DAG-SYNC节点", "dagSyncNodeHandler");
        BUILTIN_JOBS.put("内置-DAG-PYTHON节点", "dagPythonNodeHandler");
        BUILTIN_JOBS.put("内置-DAG-CONDITION节点", "dagConditionNodeHandler");
        BUILTIN_JOBS.put("内置-DAG-子DAG异步节点", "dagSubDagAsyncHandler");
    }

    private final PowerJobWorkflowClient workflowClient;

    public DagBuiltinJobRegistrar(PowerJobWorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("Ensuring builtin DAG node jobs in PowerJob, count={}", BUILTIN_JOBS.size());
        for (Map.Entry<String, String> entry : BUILTIN_JOBS.entrySet()) {
            String jobName = entry.getKey();
            String handler = entry.getValue();
            try {
                Long jobId = workflowClient.ensureBuiltinNodeJob(APP_NAME, handler, jobName);
                logger.info("Ensured builtin DAG node job: jobName={}, handler={}, jobId={}", jobName, handler, jobId);
            } catch (Exception e) {
                logger.warn("Failed to ensure builtin DAG node job: jobName={}, handler={}, will retry on next restart",
                        jobName, handler, e);
            }
        }
    }
}
