package com.datanest.alert.api;

import com.datanest.alert.api.dto.AlertFireBatchRequest;
import com.datanest.alert.api.dto.AlertFireRequest;
import com.datanest.alert.api.dto.AlertHistoryDTO;
import com.datanest.alert.api.dto.DagAlertConfigInfo;
import com.datanest.alert.api.dto.DagFinishedRequest;
import com.datanest.alert.api.dto.DagNodeTimeoutRequest;
import com.datanest.alert.api.fallback.AlertApiFallbackFactory;
import com.datanest.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 告警服务内部 Feign 契约。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-alert 的 /alert/internal/** 端点，
 * 由 InternalTokenFilter 做内部令牌鉴权。
 */
@FeignClient(name = "data-nest-alert", path = "/alert/internal", contextId = "alertApi",
        fallbackFactory = AlertApiFallbackFactory.class)
public interface AlertApi {

    /** 触发单条告警 */
    @PostMapping("/fired")
    Result<Boolean> fire(@RequestBody AlertFireRequest request);

    /** 批量触发告警 */
    @PostMapping("/fired/batch")
    Result<Boolean> fireBatch(@RequestBody AlertFireBatchRequest request);

    /** DAG 执行完成通知 */
    @PostMapping("/dag-finished")
    Result<Void> dagFinished(@RequestBody DagFinishedRequest request);

    /** DAG 节点超时通知 */
    @PostMapping("/dag-node-timeout")
    Result<Void> dagNodeTimeout(@RequestBody DagNodeTimeoutRequest request);

    /** 按对象删除告警规则 */
    @DeleteMapping("/rules/by-object")
    Result<Void> deleteRuleByObject(@RequestParam("objectType") String objectType, @RequestParam("objectId") Long objectId);

    /** 按对象查询引用它的告警规则名称列表（删除前引用校验用） */
    @GetMapping("/rules/by-object/names")
    Result<List<String>> listRuleNamesByObject(@RequestParam("objectType") String objectType, @RequestParam("objectId") Long objectId);

    /** 按质量批次查询告警历史 */
    @GetMapping("/histories/by-quality-batch")
    Result<List<AlertHistoryDTO>> listByQualityBatch(@RequestParam("batchId") Long batchId);

    /** 清理指定天数之前的告警历史 */
    @DeleteMapping("/histories/cleanup")
    Result<Integer> cleanupHistories(@RequestParam("beforeDays") int beforeDays);

    /** 按 DAG 解析生效的告警配置（专用配置优先，回退全局默认；无配置返回 null） */
    @GetMapping("/dag-alert-config/resolve")
    Result<DagAlertConfigInfo> resolveDagAlertConfig(@RequestParam("dagId") Long dagId);

    /** 按 DAG 删除告警配置（DAG 级联删除场景） */
    @DeleteMapping("/dag-alert-config/by-dag")
    Result<Void> deleteDagAlertConfigByDag(@RequestParam("dagId") Long dagId);

    /** 按执行实例批量删除 DAG 告警发送历史（DAG 级联删除场景） */
    @DeleteMapping("/dag-alert-histories/by-executions")
    Result<Void> deleteDagAlertHistoriesByExecutions(@RequestParam("executionIds") List<Long> executionIds);
}
