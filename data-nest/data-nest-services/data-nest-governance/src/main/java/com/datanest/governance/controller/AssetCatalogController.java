package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.AssetClassificationDTO;
import com.datanest.governance.dto.AssetClassificationTreeDTO;
import com.datanest.governance.dto.AssetSearchItemDTO;
import com.datanest.governance.dto.AssignClassificationBatchRequest;
import com.datanest.governance.dto.AssignClassificationRequest;
import com.datanest.governance.dto.AssignOwnerRequest;
import com.datanest.governance.dto.ClassificationSaveRequest;
import com.datanest.governance.service.AssetCatalogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据资产目录 Controller（Sprint 7 F1）。
 * <p>
 * DC-01 多维搜索（扁平结果，独立于 search-tree）、DC-05 分类体系维护与分类浏览、
 * DC-02 表分配分类/负责人。读接口四角色可见；写接口仅治理员/超管（PRD §8）。
 */
@RestController
@RequestMapping("/assets")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class AssetCatalogController {

    private final AssetCatalogService assetCatalogService;

    public AssetCatalogController(AssetCatalogService assetCatalogService) {
        this.assetCatalogService = assetCatalogService;
    }

    /** 资产多维搜索（表名/注释/字段/负责人），按相关度排序，回填质量分与分类。 */
    @GetMapping("/search")
    public Result<List<AssetSearchItemDTO>> search(@RequestParam String keyword,
                                                   @RequestParam(required = false) Long datasourceId,
                                                   @RequestParam(required = false) String healthLevel) {
        return Result.ok(assetCatalogService.search(keyword, datasourceId, healthLevel));
    }

    /** 分类体系树（DOMAIN→TOPIC 两级，带各分类 ONLINE 表计数与全部/未分类计数）。 */
    @GetMapping("/classifications")
    public Result<AssetClassificationTreeDTO> listClassifications() {
        return Result.ok(assetCatalogService.listClassificationTree());
    }

    /** 新增分类。 */
    @PostMapping("/classifications")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<AssetClassificationDTO> createClassification(@RequestBody ClassificationSaveRequest request) {
        return Result.ok(assetCatalogService.createClassification(request));
    }

    /** 编辑分类（改名时级联更新 metadata_table 冗余分类名）。 */
    @PutMapping("/classifications/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<AssetClassificationDTO> updateClassification(@PathVariable Long id,
                                                               @RequestBody ClassificationSaveRequest request) {
        return Result.ok(assetCatalogService.updateClassification(id, request));
    }

    /** 删除分类（仍被表引用或数据域下仍有主题时拒绝）。 */
    @DeleteMapping("/classifications/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> deleteClassification(@PathVariable Long id) {
        assetCatalogService.deleteClassification(id);
        return Result.ok(null);
    }

    /** 分类浏览资产列表（分页；uncategorized=true 查未分类；sort=score 按质量分降序；healthLevel 按健康度筛选）。 */
    @GetMapping("/browse")
    public Result<PageResult<AssetSearchItemDTO>> browse(@RequestParam(required = false) String domain,
                                                         @RequestParam(required = false) String topic,
                                                         @RequestParam(required = false) Long datasourceId,
                                                         @RequestParam(required = false) String healthLevel,
                                                         @RequestParam(defaultValue = "false") boolean uncategorized,
                                                         @RequestParam(required = false) String sort,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(assetCatalogService.browse(domain, topic, datasourceId, healthLevel, uncategorized, sort,
                page, pageSize));
    }

    /** 为表分配分类（传空清除）。 */
    @PutMapping("/tables/{tableId}/classification")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> assignClassification(@PathVariable Long tableId,
                                             @RequestBody AssignClassificationRequest request) {
        assetCatalogService.assignClassification(tableId, request);
        return Result.ok(null);
    }

    /** 批量分配分类（Sprint 7 F1 修订；传空 = 批量清除）。返回实际更新的表数。 */
    @PutMapping("/tables/classification/batch")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Integer> assignClassificationBatch(@RequestBody AssignClassificationBatchRequest request) {
        return Result.ok(assetCatalogService.assignClassificationBatch(request));
    }

    /** 为表配置负责人（传 null 清除）。 */
    @PutMapping("/tables/{tableId}/owner")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> assignOwner(@PathVariable Long tableId,
                                    @RequestBody AssignOwnerRequest request) {
        assetCatalogService.assignOwner(tableId, request);
        return Result.ok(null);
    }
}
