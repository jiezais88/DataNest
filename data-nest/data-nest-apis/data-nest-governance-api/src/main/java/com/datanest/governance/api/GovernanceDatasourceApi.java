package com.datanest.governance.api;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.AutoCreateCollectTaskRequest;
import com.datanest.governance.api.dto.DatasourceReferencesDTO;
import com.datanest.governance.api.fallback.GovernanceDatasourceApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 治理域数据源相关内部 Feign 契约（engineering 删除/保存数据源的跨域读写下沉）。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-governance 的 /governance/internal/** 端点。
 */
@FeignClient(name = "data-nest-governance", path = "/governance/internal", contextId = "governanceDatasourceApi",
        fallbackFactory = GovernanceDatasourceApiFallbackFactory.class)
public interface GovernanceDatasourceApi {

    /** 数据源在治理域的引用检查（采集任务/元数据表/质量规则） */
    @GetMapping("/datasources/{id}/references")
    Result<DatasourceReferencesDTO> getReferences(@PathVariable("id") Long id);

    /**
     * 级联删除数据源的治理域数据（governance 本地事务：metadata_column → metadata_table
     * → compliance_check_result → quality_score）。fail-closed：失败必须让调用方中止删除。
     */
    @PostMapping("/datasources/{id}/cascade-delete")
    Result<Void> cascadeDelete(@PathVariable("id") Long id);

    /** 数据源保存后自动创建并触发采集任务，返回 collectTaskId（失败返回 null） */
    @PostMapping("/collect-tasks/auto-create")
    Result<Long> autoCreateCollectTask(@RequestBody AutoCreateCollectTaskRequest request);

    /** 按 dag_id 删除血缘记录，返回删除条数（DAG 删除时的级联清理，最终一致） */
    @DeleteMapping("/lineage/by-dag/{dagId}")
    Result<Integer> deleteLineageByDag(@PathVariable("dagId") Long dagId);
}
