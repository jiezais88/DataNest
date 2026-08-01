package com.datanest.task.core.service;

import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;

import java.util.List;

/**
 * DAG 执行到达终态时的监听器 SPI。
 * task-core 在 dag_execution 被标记为 SUCCESS/FAILED 后回调，
 * engineering-service 可实现该接口触发邮件告警等副作用。
 */
public interface DagExecutionFinishedListener {

    void onFinished(DagExecution execution, List<NodeExecution> nodes);
}
