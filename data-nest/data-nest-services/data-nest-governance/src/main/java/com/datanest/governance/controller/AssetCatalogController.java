package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.AddCommentRequest;
import com.datanest.governance.dto.AddTagRequest;
import com.datanest.governance.dto.AssetClassificationDTO;
import com.datanest.governance.dto.AssetClassificationTreeDTO;
import com.datanest.governance.dto.AssetCollaborationDTO;
import com.datanest.governance.dto.AssetCommentDTO;
import com.datanest.governance.dto.AssetFavoriteItemDTO;
import com.datanest.governance.dto.AssetFollowItemDTO;
import com.datanest.governance.dto.AssetSearchItemDTO;
import com.datanest.governance.dto.AssetTableTagDTO;
import com.datanest.governance.dto.AssetTagDTO;
import com.datanest.governance.dto.AssignClassificationBatchRequest;
import com.datanest.governance.dto.AssignClassificationRequest;
import com.datanest.governance.dto.AssignOwnerRequest;
import com.datanest.governance.dto.ClassificationSaveRequest;
import com.datanest.governance.service.AssetCatalogService;
import com.datanest.governance.service.AssetCollaborationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据资产目录 Controller（Sprint 7 F1 + Sprint 8 F1）。
 * <p>
 * DC-01 多维搜索（扁平结果，独立于 search-tree）、DC-05 分类体系维护与分类浏览、
 * DC-02 表分配分类/负责人。读接口四角色可见；写接口仅治理员/超管（PRD §8）。
 * Sprint 8 F1（DC-06~09）：标签/收藏/关注/评论/热度协作端点，全角色可用；
 * 删除他人评论在服务层收窄到治理员/超管（作者可删自己的评论）。
 */
@Tag(name = "数据资产目录", description = "资产多维搜索 / 分类体系维护 / 分类与负责人分配 / 标签 / 收藏 / 关注 / 评论 / 热度")
@RestController
@RequestMapping("/assets")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class AssetCatalogController {

    private final AssetCatalogService assetCatalogService;
    private final AssetCollaborationService assetCollaborationService;

    public AssetCatalogController(AssetCatalogService assetCatalogService,
                                  AssetCollaborationService assetCollaborationService) {
        this.assetCatalogService = assetCatalogService;
        this.assetCollaborationService = assetCollaborationService;
    }

    @Operation(summary = "资产多维搜索", description = "按表名/注释/字段/负责人搜索，按相关度排序，回填质量分与分类")
    @GetMapping("/search")
    public Result<List<AssetSearchItemDTO>> search(@Parameter(description = "关键字（表名/注释/字段/负责人）") @RequestParam String keyword,
                                                   @Parameter(description = "数据源 ID") @RequestParam(required = false) Long datasourceId,
                                                   @Parameter(description = "健康度（EXCELLENT/GOOD/WARNING/BAD）") @RequestParam(required = false) String healthLevel) {
        return Result.ok(assetCatalogService.search(keyword, datasourceId, healthLevel));
    }

    @Operation(summary = "分类体系树", description = "DOMAIN→TOPIC 两级，带各分类 ONLINE 表计数与全部/未分类计数")
    @GetMapping("/classifications")
    public Result<AssetClassificationTreeDTO> listClassifications() {
        return Result.ok(assetCatalogService.listClassificationTree());
    }

    @Operation(summary = "新增分类")
    @PostMapping("/classifications")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<AssetClassificationDTO> createClassification(@RequestBody ClassificationSaveRequest request) {
        return Result.ok(assetCatalogService.createClassification(request));
    }

    @Operation(summary = "编辑分类", description = "改名时级联更新 metadata_table 冗余分类名")
    @PutMapping("/classifications/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<AssetClassificationDTO> updateClassification(@Parameter(description = "分类 ID") @PathVariable Long id,
                                                               @RequestBody ClassificationSaveRequest request) {
        return Result.ok(assetCatalogService.updateClassification(id, request));
    }

    @Operation(summary = "删除分类", description = "仍被表引用或数据域下仍有主题时拒绝")
    @DeleteMapping("/classifications/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> deleteClassification(@Parameter(description = "分类 ID") @PathVariable Long id) {
        assetCatalogService.deleteClassification(id);
        return Result.ok(null);
    }

    @Operation(summary = "分类浏览资产列表", description = "分页；uncategorized=true 查未分类；sort=score 按质量分降序、sort=hot 按最近 30 天热度降序；healthLevel 按健康度筛选；tag 按标签名筛选")
    @GetMapping("/browse")
    public Result<PageResult<AssetSearchItemDTO>> browse(@Parameter(description = "数据域名称") @RequestParam(required = false) String domain,
                                                         @Parameter(description = "主题名称") @RequestParam(required = false) String topic,
                                                         @Parameter(description = "数据源 ID") @RequestParam(required = false) Long datasourceId,
                                                         @Parameter(description = "健康度（EXCELLENT/GOOD/WARNING/BAD）") @RequestParam(required = false) String healthLevel,
                                                         @Parameter(description = "是否只查未分类资产") @RequestParam(defaultValue = "false") boolean uncategorized,
                                                         @Parameter(description = "排序方式（score=按质量分降序，hot=按最近 30 天热度降序）") @RequestParam(required = false) String sort,
                                                         @Parameter(description = "标签名（按标签筛选）") @RequestParam(required = false) String tag,
                                                         @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
                                                         @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(assetCatalogService.browse(domain, topic, datasourceId, healthLevel, uncategorized, sort,
                tag, page, pageSize));
    }

    @Operation(summary = "为表分配分类", description = "传空清除")
    @PutMapping("/tables/{tableId}/classification")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> assignClassification(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                             @RequestBody AssignClassificationRequest request) {
        assetCatalogService.assignClassification(tableId, request);
        return Result.ok(null);
    }

    @Operation(summary = "批量分配分类", description = "传空 = 批量清除；返回实际更新的表数")
    @PutMapping("/tables/classification/batch")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Integer> assignClassificationBatch(@RequestBody AssignClassificationBatchRequest request) {
        return Result.ok(assetCatalogService.assignClassificationBatch(request));
    }

    @Operation(summary = "为表配置负责人", description = "传 null 清除")
    @PutMapping("/tables/{tableId}/owner")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> assignOwner(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                    @RequestBody AssignOwnerRequest request) {
        assetCatalogService.assignOwner(tableId, request);
        return Result.ok(null);
    }

    // ==================== Sprint 8 F1：资产协作（DC-06~09） ====================

    @Operation(summary = "标签字典（标签云）", description = "全部标签 + 各标签绑定表数，按绑定数降序")
    @GetMapping("/tags")
    public Result<List<AssetTagDTO>> listTags() {
        return Result.ok(assetCollaborationService.listTags());
    }

    @Operation(summary = "表标签列表", description = "某表当前绑定的标签")
    @GetMapping("/tables/{tableId}/tags")
    public Result<List<AssetTableTagDTO>> listTableTags(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(assetCollaborationService.listTableTags(tableId));
    }

    @Operation(summary = "打标签", description = "标签名已存在则复用、否则新建；uk 幂等；返回表当前标签列表")
    @PostMapping("/tables/{tableId}/tags")
    public Result<List<AssetTableTagDTO>> addTag(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                                 @RequestBody AddTagRequest request) {
        return Result.ok(assetCollaborationService.addTag(tableId, request));
    }

    @Operation(summary = "删表标签绑定", description = "幂等；标签字典无表引用时物理删除；返回表当前标签列表")
    @DeleteMapping("/tables/{tableId}/tags/{tagId}")
    public Result<List<AssetTableTagDTO>> removeTableTag(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                                         @Parameter(description = "标签 ID") @PathVariable Long tagId) {
        return Result.ok(assetCollaborationService.removeTableTag(tableId, tagId));
    }

    @Operation(summary = "详情页协作状态聚合", description = "标签 + 当前用户收藏/关注状态 + 最近 30 天热度 + 有效评论数，详情页头部一次拉取")
    @GetMapping("/tables/{tableId}/collaboration")
    public Result<AssetCollaborationDTO> getCollaboration(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(assetCollaborationService.getCollaboration(tableId));
    }

    @Operation(summary = "收藏", description = "uk 幂等，重复收藏不报错")
    @PostMapping("/tables/{tableId}/favorite")
    public Result<Void> favorite(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        assetCollaborationService.favorite(tableId);
        return Result.ok(null);
    }

    @Operation(summary = "取消收藏", description = "幂等，未收藏不报错")
    @DeleteMapping("/tables/{tableId}/favorite")
    public Result<Void> unfavorite(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        assetCollaborationService.unfavorite(tableId);
        return Result.ok(null);
    }

    @Operation(summary = "我的收藏", description = "收藏时间倒序分页，复用资产卡片字段 + 收藏时间")
    @GetMapping("/my-favorites")
    public Result<PageResult<AssetFavoriteItemDTO>> myFavorites(@Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
                                                                @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(assetCollaborationService.myFavorites(page, pageSize));
    }

    @Operation(summary = "关注", description = "uk 幂等，重复关注不报错")
    @PostMapping("/tables/{tableId}/follow")
    public Result<Void> follow(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        assetCollaborationService.follow(tableId);
        return Result.ok(null);
    }

    @Operation(summary = "取消关注", description = "幂等，未关注不报错")
    @DeleteMapping("/tables/{tableId}/follow")
    public Result<Void> unfollow(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        assetCollaborationService.unfollow(tableId);
        return Result.ok(null);
    }

    @Operation(summary = "我的关注", description = "关注时间倒序分页，每表附最近一次采集变更动态")
    @GetMapping("/my-follows")
    public Result<PageResult<AssetFollowItemDTO>> myFollows(@Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
                                                            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(assetCollaborationService.myFollows(page, pageSize));
    }

    @Operation(summary = "评论列表", description = "有效评论（deleted=0）按时间倒序分页")
    @GetMapping("/tables/{tableId}/comments")
    public Result<PageResult<AssetCommentDTO>> listComments(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                                            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
                                                            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(assetCollaborationService.listComments(tableId, page, pageSize));
    }

    @Operation(summary = "发表评论", description = "内容非空且 ≤2000 字")
    @PostMapping("/tables/{tableId}/comments")
    public Result<AssetCommentDTO> addComment(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                              @RequestBody AddCommentRequest request) {
        return Result.ok(assetCollaborationService.addComment(tableId, request));
    }

    @Operation(summary = "删除评论", description = "软删；作者可删自己的评论，治理员/超管可删任意评论（服务层校验）")
    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@Parameter(description = "评论 ID") @PathVariable Long commentId) {
        assetCollaborationService.deleteComment(commentId);
        return Result.ok(null);
    }

    @Operation(summary = "热度埋点", description = "按 (表, 当天) upsert 累加；前端详情页打开防抖上报")
    @PostMapping("/tables/{tableId}/view")
    public Result<Void> recordView(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        assetCollaborationService.recordView(tableId);
        return Result.ok(null);
    }

    @Operation(summary = "热门数据表 Top N", description = "最近 30 天热度降序，仅 ONLINE 表；limit 默认 10、封顶 50")
    @GetMapping("/hot-tables")
    public Result<List<AssetSearchItemDTO>> hotTables(@Parameter(description = "返回条数（默认 10，封顶 50）") @RequestParam(required = false) Integer limit) {
        return Result.ok(assetCollaborationService.hotTables(limit));
    }
}
