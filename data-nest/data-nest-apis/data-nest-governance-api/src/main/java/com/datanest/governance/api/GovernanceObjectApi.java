package com.datanest.governance.api;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.ObjectNameRequest;
import com.datanest.governance.api.dto.ObjectOptionDTO;
import com.datanest.governance.api.dto.QualityAutoTriggerBatchRequest;
import com.datanest.governance.api.fallback.GovernanceObjectApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 治理服务内部 Feign 契约。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-governance 的 /governance/internal/** 端点。
 */
@FeignClient(name = "data-nest-governance", path = "/governance/internal", contextId = "governanceObjectApi",
        fallbackFactory = GovernanceObjectApiFallbackFactory.class)
public interface GovernanceObjectApi {

    /** 按对象类型 + ID 列表批量查询对象名称 */
    @PostMapping("/objects/names")
    Result<Map<Long, String>> names(@RequestBody ObjectNameRequest request);

    /** 按对象类型查询告警对象下拉选项 */
    @GetMapping("/alert-objects/options")
    Result<List<ObjectOptionDTO>> options(@RequestParam("objectType") String objectType);

    /** 质量检查自动触发（批量）：同类型对象逐个触发，单个失败只记 error 不中断 */
    @PostMapping("/quality/auto-trigger/batch")
    Result<Void> qualityAutoTriggerBatch(@RequestBody QualityAutoTriggerBatchRequest request);
}
