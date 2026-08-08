package com.datanest.engineering.api;

import com.datanest.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 子 DAG 异步触发内部 Feign 契约（P4 正式版，替代 P3 临时落在 worker 侧的 EngineeringSubDagInternalApi）。
 * <p>
 * 对应 engineering 的 SubDagTriggerController（POST /dev/internal/subdag/trigger），
 * 语义：触发子 DAG 独立执行后立即返回，不等待完成、不回写父节点。
 * <p>
 * 故意不挂 fallbackFactory：触发失败必须 fail-fast 抛异常（调用方 DagSubDagAsyncHandler
 * 据此刻把节点标失败），降级吞掉会被误判为触发成功。
 */
@FeignClient(name = "data-nest-engineering", path = "/engineering/dev/internal", contextId = "engineeringSubDagApi")
public interface EngineeringSubDagApi {

    /** 触发子 DAG 独立执行（不等待结果）。body: { dagId, nodeId, subDagId, subDagName } */
    @PostMapping("/subdag/trigger")
    Result<Map<String, Object>> triggerSubDag(@RequestBody Map<String, Object> body);
}
