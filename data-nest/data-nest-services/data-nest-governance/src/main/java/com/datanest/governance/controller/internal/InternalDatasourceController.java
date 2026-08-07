package com.datanest.governance.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.AutoCreateCollectTaskRequest;
import com.datanest.governance.api.dto.DatasourceReferencesDTO;
import com.datanest.governance.service.internal.InternalDatasourceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治理域数据源内部接口（实现 governance-api 的 GovernanceDatasourceApi 契约）。
 * <p>
 * 仅供服务间内部调用（engineering 删除/保存数据源时的跨域读写），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal")
public class InternalDatasourceController {

    private final InternalDatasourceService internalDatasourceService;

    public InternalDatasourceController(InternalDatasourceService internalDatasourceService) {
        this.internalDatasourceService = internalDatasourceService;
    }

    /** 数据源在治理域的引用检查（删除数据源前调用）。 */
    @GetMapping("/datasources/{id}/references")
    public Result<DatasourceReferencesDTO> getReferences(@PathVariable("id") Long id) {
        return Result.ok(internalDatasourceService.getReferences(id));
    }

    /** 级联删除数据源的治理域数据（本地事务）。 */
    @PostMapping("/datasources/{id}/cascade-delete")
    public Result<Void> cascadeDelete(@PathVariable("id") Long id) {
        internalDatasourceService.cascadeDelete(id);
        return Result.ok(null);
    }

    /** 数据源保存后自动创建并触发采集任务，返回 collectTaskId。 */
    @PostMapping("/collect-tasks/auto-create")
    public Result<Long> autoCreateCollectTask(@RequestBody AutoCreateCollectTaskRequest request) {
        return Result.ok(internalDatasourceService.autoCreateCollectTask(request));
    }

    /** 按 dag_id 删除血缘记录，返回删除条数。 */
    @DeleteMapping("/lineage/by-dag/{dagId}")
    public Result<Integer> deleteLineageByDag(@PathVariable("dagId") Long dagId) {
        return Result.ok(internalDatasourceService.deleteLineageByDag(dagId));
    }
}
