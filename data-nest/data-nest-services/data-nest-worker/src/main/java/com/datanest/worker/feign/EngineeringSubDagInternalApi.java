package com.datanest.worker.feign;

import com.datanest.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * engineering 子 DAG 异步触发内部端点 Feign 契约（P3 临时落在 worker 侧）。
 * <p>
 * 对应 engineering 的 SubDagTriggerController（POST /dev/internal/subdag/trigger），
 * 语义与 DS HTTP 任务回调一致：触发子 DAG 独立执行后立即返回，不等待完成、不回写父节点。
 * <p>
 * TODO(P3 集成)：engineering-api 模块尚无该端点的正式 Feign 契约，集成方请在
 * EngineeringDagApi（或新建 EngineeringSubDagApi）补充同签名端点后，把本接口删除并切换引用。
 */
@FeignClient(name = "data-nest-engineering", path = "/dev/internal", contextId = "engineeringSubDagInternalApi")
public interface EngineeringSubDagInternalApi {

    /** 触发子 DAG 独立执行（不等待结果）。body: { dagId, nodeId, subDagId, subDagName } */
    @PostMapping("/subdag/trigger")
    Result<Map<String, Object>> triggerSubDag(@RequestBody Map<String, Object> body);
}
