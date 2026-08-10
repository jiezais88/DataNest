package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.AddCommentRequest;
import com.datanest.governance.dto.AddTagRequest;
import com.datanest.governance.dto.AssetChangeDTO;
import com.datanest.governance.dto.AssetCollaborationDTO;
import com.datanest.governance.dto.AssetCommentDTO;
import com.datanest.governance.dto.AssetFavoriteItemDTO;
import com.datanest.governance.dto.AssetFollowItemDTO;
import com.datanest.governance.dto.AssetSearchItemDTO;
import com.datanest.governance.dto.AssetTableTagDTO;
import com.datanest.governance.dto.AssetTagDTO;
import com.datanest.governance.entity.AssetComment;
import com.datanest.governance.entity.AssetFavorite;
import com.datanest.governance.entity.AssetFollow;
import com.datanest.governance.entity.AssetTableTag;
import com.datanest.governance.entity.AssetTag;
import com.datanest.governance.entity.AssetViewLog;
import com.datanest.governance.entity.CollectChangeDetail;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.mapper.AssetCommentMapper;
import com.datanest.governance.mapper.AssetFavoriteMapper;
import com.datanest.governance.mapper.AssetFollowMapper;
import com.datanest.governance.mapper.AssetTableTagMapper;
import com.datanest.governance.mapper.AssetTagMapper;
import com.datanest.governance.mapper.AssetViewLogMapper;
import com.datanest.governance.mapper.CollectChangeDetailMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.system.api.SystemUserApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资产协作服务（Sprint 8 F1，DC-06~09）。
 * <p>
 * 数据标签（平台级字典 + 表绑定，同名复用）、收藏与关注（个人维度，uk 幂等）、
 * 评论（按表维度，软删）、热度（asset_view_log 按天 upsert 累加，30 天窗口聚合）。
 * 卡片字段（质量分/负责人/数据源/标签）复用 {@link AssetCatalogService} 的 toItemDTO/backfill。
 */
@Slf4j
@Service
public class AssetCollaborationService {

    private static final int MAX_TAG_NAME_LENGTH = 100;
    private static final int MAX_COMMENT_LENGTH = 2000;
    /** 热度统计窗口：最近 30 天（技术文档 D-D5） */
    private static final int HOT_WINDOW_DAYS = 30;
    /** 热门排行上限保护（limit 参数封顶） */
    private static final int MAX_HOT_TABLES = 50;

    private final MetadataTableMapper metadataTableMapper;
    private final AssetTagMapper assetTagMapper;
    private final AssetTableTagMapper assetTableTagMapper;
    private final AssetFavoriteMapper assetFavoriteMapper;
    private final AssetFollowMapper assetFollowMapper;
    private final AssetCommentMapper assetCommentMapper;
    private final AssetViewLogMapper assetViewLogMapper;
    private final CollectChangeDetailMapper collectChangeDetailMapper;
    private final SystemUserApi systemUserApi;
    private final AssetCatalogService assetCatalogService;

    public AssetCollaborationService(MetadataTableMapper metadataTableMapper,
                                     AssetTagMapper assetTagMapper,
                                     AssetTableTagMapper assetTableTagMapper,
                                     AssetFavoriteMapper assetFavoriteMapper,
                                     AssetFollowMapper assetFollowMapper,
                                     AssetCommentMapper assetCommentMapper,
                                     AssetViewLogMapper assetViewLogMapper,
                                     CollectChangeDetailMapper collectChangeDetailMapper,
                                     SystemUserApi systemUserApi,
                                     AssetCatalogService assetCatalogService) {
        this.metadataTableMapper = metadataTableMapper;
        this.assetTagMapper = assetTagMapper;
        this.assetTableTagMapper = assetTableTagMapper;
        this.assetFavoriteMapper = assetFavoriteMapper;
        this.assetFollowMapper = assetFollowMapper;
        this.assetCommentMapper = assetCommentMapper;
        this.assetViewLogMapper = assetViewLogMapper;
        this.collectChangeDetailMapper = collectChangeDetailMapper;
        this.systemUserApi = systemUserApi;
        this.assetCatalogService = assetCatalogService;
    }

    // ==================== DC-06 数据标签 ====================

    /** 全部标签字典 + 各标签绑定表数（标签云）。 */
    public List<AssetTagDTO> listTags() {
        return assetTagMapper.selectTagCloud().stream().map(row -> {
            AssetTagDTO dto = new AssetTagDTO();
            dto.setTagId(((Number) row.get("id")).longValue());
            dto.setTagName((String) row.get("name"));
            dto.setRefCount(((Number) row.get("ref_count")).longValue());
            return dto;
        }).toList();
    }

    /** 某表当前绑定的标签列表。 */
    public List<AssetTableTagDTO> listTableTags(Long tableId) {
        return assetTableTagMapper.selectTagRowsByTableIds(List.of(tableId)).stream().map(row -> {
            AssetTableTagDTO dto = new AssetTableTagDTO();
            dto.setTagId(((Number) row.get("tag_id")).longValue());
            dto.setTagName((String) row.get("tag_name"));
            return dto;
        }).toList();
    }

    /**
     * 打标签：标签名已存在则复用、否则新建，再写表-标签绑定（uk 幂等），返回表当前标签列表。
     * 刻意不加 @Transactional：PG 事务内捕获唯一冲突后整事务已 aborted 无法续查，
     * 而「建标签 + 绑定」均为幂等写，并发冲突走 catch 回查即可，无需原子性。
     */
    public List<AssetTableTagDTO> addTag(Long tableId, AddTagRequest request) {
        requireTable(tableId);
        String tagName = request == null ? null : trimToNull(request.getTagName());
        if (tagName == null) {
            throw new BusinessException(ErrorCode.ASSET_COLLABORATION_INVALID, "标签名不能为空");
        }
        if (tagName.length() > MAX_TAG_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.ASSET_COLLABORATION_INVALID,
                    "标签名不能超过 " + MAX_TAG_NAME_LENGTH + " 字");
        }

        AssetTag tag = findTagByName(tagName);
        if (tag == null) {
            tag = new AssetTag();
            tag.setName(tagName);
            tag.setCreatedBy(currentUserId());
            tag.setCreatedAt(LocalDateTime.now());
            try {
                assetTagMapper.insert(tag);
            } catch (DuplicateKeyException e) {
                // 并发创建同名标签：回查复用已有字典行
                tag = findTagByName(tagName);
            }
        }

        Long bound = assetTableTagMapper.selectCount(new QueryWrapper<AssetTableTag>()
                .eq("table_id", tableId).eq("tag_id", tag.getId()));
        if (bound == null || bound == 0) {
            AssetTableTag binding = new AssetTableTag();
            binding.setTableId(tableId);
            binding.setTagId(tag.getId());
            binding.setCreatedBy(currentUserId());
            binding.setCreatedAt(LocalDateTime.now());
            try {
                assetTableTagMapper.insert(binding);
            } catch (DuplicateKeyException e) {
                // 并发重复绑定：uk 幂等，忽略
            }
        }
        return listTableTags(tableId);
    }

    /**
     * 删表标签绑定（幂等）；标签字典无表引用时物理删除（技术文档 §4.1）。返回表当前标签列表。
     */
    public List<AssetTableTagDTO> removeTableTag(Long tableId, Long tagId) {
        int removed = assetTableTagMapper.delete(new QueryWrapper<AssetTableTag>()
                .eq("table_id", tableId).eq("tag_id", tagId));
        if (removed > 0) {
            Long refs = assetTableTagMapper.selectCount(new QueryWrapper<AssetTableTag>().eq("tag_id", tagId));
            if (refs == null || refs == 0) {
                assetTagMapper.deleteById(tagId);
            }
        }
        return listTableTags(tableId);
    }

    // ==================== 详情页协作状态聚合 ====================

    /** 详情页头部协作状态一次拉取：标签 + 当前用户收藏/关注状态 + 30 天热度 + 有效评论数。 */
    public AssetCollaborationDTO getCollaboration(Long tableId) {
        requireTable(tableId);
        Long userId = currentUserId();
        AssetCollaborationDTO dto = new AssetCollaborationDTO();
        dto.setTags(listTableTags(tableId));
        dto.setFavorited(existsFavorite(userId, tableId));
        dto.setFollowed(existsFollow(userId, tableId));
        dto.setViewCount30d(sumViewCount30d(List.of(tableId)).getOrDefault(tableId, 0L));
        Long commentCount = assetCommentMapper.selectCount(new QueryWrapper<AssetComment>()
                .eq("table_id", tableId).eq("deleted", 0));
        dto.setCommentCount(commentCount == null ? 0L : commentCount);
        return dto;
    }

    // ==================== DC-07 收藏与关注 ====================

    /** 收藏（uk 幂等，重复收藏不报错）。 */
    public void favorite(Long tableId) {
        requireTable(tableId);
        Long userId = currentUserId();
        if (existsFavorite(userId, tableId)) {
            return;
        }
        AssetFavorite favorite = new AssetFavorite();
        favorite.setUserId(userId);
        favorite.setTableId(tableId);
        favorite.setCreatedAt(LocalDateTime.now());
        try {
            assetFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 并发重复收藏：uk 幂等，忽略
        }
    }

    /** 取消收藏（幂等，未收藏不报错）。 */
    public void unfavorite(Long tableId) {
        assetFavoriteMapper.delete(new QueryWrapper<AssetFavorite>()
                .eq("user_id", currentUserId()).eq("table_id", tableId));
    }

    /** 我的收藏：收藏时间倒序分页，复用资产卡片字段回填。 */
    public PageResult<AssetFavoriteItemDTO> myFavorites(int page, int pageSize) {
        IPage<AssetFavorite> mpPage = assetFavoriteMapper.selectPage(new Page<>(page, pageSize),
                new QueryWrapper<AssetFavorite>().eq("user_id", currentUserId()).orderByDesc("created_at"));
        Map<Long, MetadataTable> tableMap = tablesByIds(
                mpPage.getRecords().stream().map(AssetFavorite::getTableId).toList());
        List<AssetFavoriteItemDTO> items = new ArrayList<>();
        for (AssetFavorite f : mpPage.getRecords()) {
            MetadataTable t = tableMap.get(f.getTableId());
            if (t == null) {
                // 表已物理删除但级联清理未覆盖到的历史数据，兜底跳过
                continue;
            }
            AssetFavoriteItemDTO dto = new AssetFavoriteItemDTO();
            BeanUtils.copyProperties(assetCatalogService.toItemDTO(t, null), dto);
            dto.setFavoritedAt(f.getCreatedAt());
            items.add(dto);
        }
        assetCatalogService.backfill(items);
        return new PageResult<>(items, mpPage.getTotal(), mpPage.getCurrent(), mpPage.getSize());
    }

    /** 关注（uk 幂等，重复关注不报错）。 */
    public void follow(Long tableId) {
        requireTable(tableId);
        Long userId = currentUserId();
        if (existsFollow(userId, tableId)) {
            return;
        }
        AssetFollow follow = new AssetFollow();
        follow.setUserId(userId);
        follow.setTableId(tableId);
        follow.setCreatedAt(LocalDateTime.now());
        try {
            assetFollowMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            // 并发重复关注：uk 幂等，忽略
        }
    }

    /** 取消关注（幂等，未关注不报错）。 */
    public void unfollow(Long tableId) {
        assetFollowMapper.delete(new QueryWrapper<AssetFollow>()
                .eq("user_id", currentUserId()).eq("table_id", tableId));
    }

    /** 我的关注：关注时间倒序分页，每表附最近一次采集变更动态（collect_change_detail 三元组匹配）。 */
    public PageResult<AssetFollowItemDTO> myFollows(int page, int pageSize) {
        IPage<AssetFollow> mpPage = assetFollowMapper.selectPage(new Page<>(page, pageSize),
                new QueryWrapper<AssetFollow>().eq("user_id", currentUserId()).orderByDesc("created_at"));
        Map<Long, MetadataTable> tableMap = tablesByIds(
                mpPage.getRecords().stream().map(AssetFollow::getTableId).toList());
        List<AssetFollowItemDTO> items = new ArrayList<>();
        for (AssetFollow f : mpPage.getRecords()) {
            MetadataTable t = tableMap.get(f.getTableId());
            if (t == null) {
                continue;
            }
            AssetFollowItemDTO dto = new AssetFollowItemDTO();
            BeanUtils.copyProperties(assetCatalogService.toItemDTO(t, null), dto);
            dto.setFollowedAt(f.getCreatedAt());
            dto.setLatestChange(latestChange(t));
            items.add(dto);
        }
        assetCatalogService.backfill(items);
        return new PageResult<>(items, mpPage.getTotal(), mpPage.getCurrent(), mpPage.getSize());
    }

    // ==================== DC-08 评论与讨论 ====================

    /** 评论列表：有效评论（deleted=0）按 id 倒序分页，批量回填评论人用户名。 */
    public PageResult<AssetCommentDTO> listComments(Long tableId, int page, int pageSize) {
        IPage<AssetComment> mpPage = assetCommentMapper.selectPage(new Page<>(page, pageSize),
                new QueryWrapper<AssetComment>().eq("table_id", tableId).eq("deleted", 0).orderByDesc("id"));
        List<AssetCommentDTO> items = mpPage.getRecords().stream()
                .map(this::toCommentDTO)
                .collect(Collectors.toCollection(ArrayList::new));
        backfillCommentUsernames(items);
        return new PageResult<>(items, mpPage.getTotal(), mpPage.getCurrent(), mpPage.getSize());
    }

    /** 发表评论：内容非空且 ≤2000 字。 */
    public AssetCommentDTO addComment(Long tableId, AddCommentRequest request) {
        requireTable(tableId);
        String content = request == null ? null : trimToNull(request.getContent());
        if (content == null) {
            throw new BusinessException(ErrorCode.ASSET_COLLABORATION_INVALID, "评论内容不能为空");
        }
        if (content.length() > MAX_COMMENT_LENGTH) {
            throw new BusinessException(ErrorCode.ASSET_COLLABORATION_INVALID,
                    "评论内容不能超过 " + MAX_COMMENT_LENGTH + " 字");
        }
        AssetComment comment = new AssetComment();
        comment.setTableId(tableId);
        comment.setUserId(currentUserId());
        comment.setContent(content);
        comment.setDeleted(0);
        comment.setCreatedBy(currentUserId());
        comment.setCreatedAt(LocalDateTime.now());
        assetCommentMapper.insert(comment);

        AssetCommentDTO dto = toCommentDTO(comment);
        backfillCommentUsernames(new ArrayList<>(List.of(dto)));
        return dto;
    }

    /**
     * 删除评论（软删）：作者可删自己评论；治理员/超管可删任意评论（PRD §8）。
     * 软删统一记录删除人/删除时间（2026-08-10 用户确认补 deleted_by/deleted_at）。
     */
    public void deleteComment(Long commentId) {
        AssetComment comment = assetCommentMapper.selectById(commentId);
        if (comment == null || Integer.valueOf(1).equals(comment.getDeleted())) {
            throw new BusinessException(ErrorCode.ASSET_COMMENT_NOT_FOUND);
        }
        Long userId = currentUserId();
        boolean isAuthor = Objects.equals(comment.getUserId(), userId);
        if (!isAuthor && !StpUtil.hasRole("SUPER_ADMIN") && !StpUtil.hasRole("GOVERNANCE_ADMIN")) {
            throw new BusinessException(ErrorCode.ASSET_COMMENT_DELETE_FORBIDDEN);
        }
        comment.setDeleted(1);
        comment.setDeletedBy(userId);
        comment.setDeletedAt(LocalDateTime.now());
        assetCommentMapper.updateById(comment);
    }

    // ==================== DC-09 热度排行 ====================

    /** 热度埋点：按 (table_id, 当天) upsert 累加（前端已做会话级防抖去重，PRD NAC-4）。 */
    public void recordView(Long tableId) {
        requireTable(tableId);
        assetViewLogMapper.upsertIncrement(IdWorker.getId(), tableId, LocalDate.now());
    }

    /** 热门数据表 Top N：最近 30 天热度降序，仅 ONLINE 表，复用资产卡片字段回填。 */
    public List<AssetSearchItemDTO> hotTables(Integer limit) {
        int top = limit == null || limit <= 0 ? 10 : Math.min(limit, MAX_HOT_TABLES);
        List<Map<String, Object>> rows = assetViewLogMapper.selectHotTables(
                LocalDate.now().minusDays(HOT_WINDOW_DAYS), top);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, MetadataTable> tableMap = tablesByIds(
                rows.stream().map(r -> ((Number) r.get("table_id")).longValue()).toList());
        List<AssetSearchItemDTO> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long tableId = ((Number) row.get("table_id")).longValue();
            MetadataTable t = tableMap.get(tableId);
            if (t == null) {
                continue;
            }
            AssetSearchItemDTO dto = assetCatalogService.toItemDTO(t, null);
            dto.setViewCount(((Number) row.get("view_count")).longValue());
            items.add(dto);
        }
        assetCatalogService.backfill(items);
        return items;
    }

    // ==================== 表删除级联清理（删除钩子，PRD §7 / 技术文档 T4） ====================

    /**
     * 表删除级联清理协作数据：标签绑定 / 收藏 / 关注 / 评论（物理删）/ 热度记录。
     * 绑定清理后顺带删除无引用的标签字典行。
     * 调用方：MetadataWriteService.remove（DROP TABLE）、InternalDatasourceService.cascadeDelete（删数据源）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTableIds(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return;
        }
        assetTableTagMapper.delete(new QueryWrapper<AssetTableTag>().in("table_id", tableIds));
        int tagsRemoved = assetTagMapper.deleteOrphanTags();
        int favoritesRemoved = assetFavoriteMapper.delete(
                new QueryWrapper<AssetFavorite>().in("table_id", tableIds));
        int followsRemoved = assetFollowMapper.delete(
                new QueryWrapper<AssetFollow>().in("table_id", tableIds));
        int commentsRemoved = assetCommentMapper.delete(
                new QueryWrapper<AssetComment>().in("table_id", tableIds));
        int viewsRemoved = assetViewLogMapper.delete(
                new QueryWrapper<AssetViewLog>().in("table_id", tableIds));
        log.info("表删除级联清理协作数据: tables={}, 孤儿标签={}, 收藏={}, 关注={}, 评论={}, 热度={}",
                tableIds.size(), tagsRemoved, favoritesRemoved, followsRemoved, commentsRemoved, viewsRemoved);
    }

    // ==================== private ====================

    private void requireTable(Long tableId) {
        if (metadataTableMapper.selectById(tableId) == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
    }

    private AssetTag findTagByName(String tagName) {
        return assetTagMapper.selectOne(new QueryWrapper<AssetTag>()
                .eq("name", tagName).last("limit 1"));
    }

    private boolean existsFavorite(Long userId, Long tableId) {
        Long count = assetFavoriteMapper.selectCount(new QueryWrapper<AssetFavorite>()
                .eq("user_id", userId).eq("table_id", tableId));
        return count != null && count > 0;
    }

    private boolean existsFollow(Long userId, Long tableId) {
        Long count = assetFollowMapper.selectCount(new QueryWrapper<AssetFollow>()
                .eq("user_id", userId).eq("table_id", tableId));
        return count != null && count > 0;
    }

    private Map<Long, MetadataTable> tablesByIds(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return Map.of();
        }
        return metadataTableMapper.selectBatchIds(tableIds).stream()
                .collect(Collectors.toMap(MetadataTable::getId, Function.identity()));
    }

    /** 批量聚合最近 30 天访问数（tableId → viewCount）。 */
    private Map<Long, Long> sumViewCount30d(List<Long> tableIds) {
        Map<Long, Long> map = new HashMap<>();
        for (Map<String, Object> row : assetViewLogMapper.sumViewCountByTableIds(
                tableIds, LocalDate.now().minusDays(HOT_WINDOW_DAYS))) {
            map.put(((Number) row.get("table_id")).longValue(), ((Number) row.get("view_count")).longValue());
        }
        return map;
    }

    /** 关注表的最近一次采集变更：按 database/schema/table 三元组匹配取 id 最大一条（技术文档 §4.1）。 */
    private AssetChangeDTO latestChange(MetadataTable t) {
        CollectChangeDetail detail = collectChangeDetailMapper.selectOne(new QueryWrapper<CollectChangeDetail>()
                .eq("database_name", t.getDatabaseName())
                .apply("COALESCE(schema_name, '') = {0}", t.getSchemaName() == null ? "" : t.getSchemaName())
                .eq("table_name", t.getTableName())
                .orderByDesc("id")
                .last("limit 1"));
        if (detail == null) {
            return null;
        }
        AssetChangeDTO dto = new AssetChangeDTO();
        dto.setChangeType(detail.getChangeType());
        dto.setColumnName(detail.getColumnName());
        dto.setOldValue(detail.getOldValue());
        dto.setNewValue(detail.getNewValue());
        dto.setChangeTime(detail.getCreatedAt());
        return dto;
    }

    private AssetCommentDTO toCommentDTO(AssetComment comment) {
        AssetCommentDTO dto = new AssetCommentDTO();
        dto.setCommentId(comment.getId());
        dto.setTableId(comment.getTableId());
        dto.setUserId(comment.getUserId());
        dto.setContent(comment.getContent());
        dto.setCreatedAt(comment.getCreatedAt());
        return dto;
    }

    /**
     * 批量回填评论人用户名（技术文档 D-D4）：降级值 null 区分两种缺名场景——
     * system 服务不可用显示「—」；服务正常但查无用户（用户已物理删）显示「已注销」（B4 已定稿）。
     */
    private void backfillCommentUsernames(List<AssetCommentDTO> items) {
        List<Long> userIds = items.stream().map(AssetCommentDTO::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> usernameMap = RemoteCalls.execute("system.usernames", () -> {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds);
            return result == null || result.data() == null ? Map.<Long, String>of() : result.data();
        }, null);
        for (AssetCommentDTO item : items) {
            if (usernameMap == null) {
                item.setUsername("—");
            } else {
                item.setUsername(usernameMap.getOrDefault(item.getUserId(), "已注销"));
            }
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
