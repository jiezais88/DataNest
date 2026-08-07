package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.constant.SourceType;
import com.datanest.common.model.PageResult;
import com.datanest.governance.dto.AssetClassificationDTO;
import com.datanest.governance.dto.AssetClassificationTreeDTO;
import com.datanest.governance.dto.AssetSearchItemDTO;
import com.datanest.governance.dto.AssignClassificationBatchRequest;
import com.datanest.governance.dto.AssignClassificationRequest;
import com.datanest.governance.dto.AssignOwnerRequest;
import com.datanest.governance.dto.ClassificationSaveRequest;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.task.core.dto.QualityScoreDTO;
import com.datanest.governance.entity.AssetClassification;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.mapper.AssetClassificationMapper;
import com.datanest.governance.mapper.MetadataColumnMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.governance.service.QualityScoreService;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.system.api.SystemUserApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据资产目录服务（Sprint 7 F1）。
 * <p>
 * 多维搜索（表名/注释/字段/负责人）+ 相关度排序 + 质量分回填（DC-01）；
 * 分类体系维护与分类浏览（DC-05）；表分配分类/负责人（DC-02）。
 * 血缘/质量详情页数据由前端复用现有 API 组装，本服务不新建聚合大接口（技术文档 D2/§4.2）。
 */
@Slf4j
@Service
public class AssetCatalogService {

    /** 搜索关键词最大长度，超长截断以保护 LIKE 查询（对齐 MetadataService.searchTree） */
    private static final int MAX_SEARCH_KEYWORD_LENGTH = 100;
    /** 分类浏览 sort=score 内存排序时的最大拉取条数（千级表规模兜底） */
    private static final int MAX_BROWSABLE_ROWS = 1000;

    /** 相关度权重：表名命中 > 注释命中 > 字段命中 > 负责人命中（技术文档 §4.1） */
    private static final int SCORE_TABLE_NAME = 100;
    private static final int SCORE_COMMENT = 60;
    private static final int SCORE_COLUMN = 40;
    private static final int SCORE_OWNER = 20;
    /** 表名前缀命中加成（前缀命中优先） */
    private static final int SCORE_PREFIX_BONUS = 20;

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final AssetClassificationMapper classificationMapper;
    private final EngineeringDatasourceApi datasourceApi;
    private final SystemUserApi systemUserApi;
    private final QualityScoreService qualityScoreService;

    /** 资产搜索结果裁剪上限（对齐 searchTree 的 MAX_SEARCH_RESULTS 保护） */
    @Value("${datanest.asset.search.max-results:200}")
    private int maxSearchResults;

    public AssetCatalogService(MetadataTableMapper metadataTableMapper,
                               MetadataColumnMapper metadataColumnMapper,
                               AssetClassificationMapper classificationMapper,
                               EngineeringDatasourceApi datasourceApi,
                               SystemUserApi systemUserApi,
                               QualityScoreService qualityScoreService) {
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.classificationMapper = classificationMapper;
        this.datasourceApi = datasourceApi;
        this.systemUserApi = systemUserApi;
        this.qualityScoreService = qualityScoreService;
    }

    // ==================== DC-01 资产搜索 ====================

    /**
     * 多维资产搜索：表名/注释/字段名/负责人模糊匹配，按相关度排序，回填质量分与分类。
     * 标签维度本期预留（无标签表，PRD §6.2）。
     * 可选过滤：datasourceId 按数据源收窄；healthLevel 按健康度收窄（经 quality_score 反查表 ID）。
     */
    public List<AssetSearchItemDTO> search(String keyword, Long datasourceId, String healthLevel) {
        String trimmed = cleanKeyword(keyword);
        if (trimmed == null) {
            return List.of();
        }
        // 健康度筛选：反查无命中即无结果（避免把空 IN 拼进 SQL）
        List<Long> healthTableIds = null;
        if (healthLevel != null && !healthLevel.isBlank()) {
            healthTableIds = qualityScoreService.findTableIdsByHealthLevel(healthLevel);
            if (healthTableIds.isEmpty()) {
                return List.of();
            }
        }
        // 负责人维度：关键词反查 userId；字段维度：关键词反查 tableId
        List<Long> ownerUserIds = findUserIdsByNameKeyword(trimmed);
        List<Long> columnHitTableIds = metadataColumnMapper.selectTableIdsByColumnKeyword(trimmed);

        List<MetadataTable> rows = metadataTableMapper.searchAssetTables(
                trimmed, ownerUserIds, columnHitTableIds, datasourceId, healthTableIds, maxSearchResults);
        if (rows.size() >= maxSearchResults) {
            log.warn("资产搜索结果达到上限 {}，已截断（keyword={}）", maxSearchResults, trimmed);
        }

        Set<Long> columnHitSet = new HashSet<>(columnHitTableIds);
        Set<Long> ownerHitSet = new HashSet<>(ownerUserIds);
        String lowerKeyword = trimmed.toLowerCase();

        List<AssetSearchItemDTO> items = rows.stream()
                .map(t -> toItemDTO(t, computeScore(t, lowerKeyword, columnHitSet, ownerHitSet)))
                .sorted(Comparator.comparing(AssetSearchItemDTO::getScore).reversed())
                .toList();
        backfill(items);
        return items;
    }

    /** 关键词清理：空白/纯通配符拒绝返回 null；超长截断（对齐 searchTree）。 */
    private String cleanKeyword(String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.replace("%", "").replace("_", "").isBlank()) {
            return null;
        }
        if (trimmed.length() > MAX_SEARCH_KEYWORD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_SEARCH_KEYWORD_LENGTH);
        }
        return trimmed;
    }

    /** 相关度得分：取命中维度的最高权重，表名前缀命中加成。 */
    private int computeScore(MetadataTable t, String lowerKeyword,
                             Set<Long> columnHitSet, Set<Long> ownerHitSet) {
        int score = 0;
        if (containsIgnoreCase(t.getTableName(), lowerKeyword)) {
            score = SCORE_TABLE_NAME;
            if (t.getTableName() != null && t.getTableName().toLowerCase().startsWith(lowerKeyword)) {
                score += SCORE_PREFIX_BONUS;
            }
        }
        if (score < SCORE_COMMENT
                && (containsIgnoreCase(t.getTableComment(), lowerKeyword)
                || containsIgnoreCase(t.getManualComment(), lowerKeyword))) {
            score = SCORE_COMMENT;
        }
        if (score < SCORE_COLUMN && columnHitSet.contains(t.getId())) {
            score = SCORE_COLUMN;
        }
        if (score < SCORE_OWNER && t.getOwnerUserId() != null && ownerHitSet.contains(t.getOwnerUserId())) {
            score = SCORE_OWNER;
        }
        return score;
    }

    private boolean containsIgnoreCase(String field, String lowerKeyword) {
        return field != null && field.toLowerCase().contains(lowerKeyword);
    }

    // ==================== DC-05 分类体系维护 ====================

    /** 分类体系树（DOMAIN→TOPIC 两级，同级按 sort 排序），带各分类 ONLINE 表计数。 */
    public AssetClassificationTreeDTO listClassificationTree() {
        List<AssetClassification> all = classificationMapper.selectList(
                new QueryWrapper<AssetClassification>().orderByAsc("sort").orderByAsc("id"));
        Map<Long, AssetClassificationDTO> dtoMap = all.stream()
                .map(this::toClassificationDTO)
                .collect(Collectors.toMap(AssetClassificationDTO::getId, Function.identity(),
                        (a, b) -> a, HashMap::new));
        List<AssetClassificationDTO> roots = new ArrayList<>();
        for (AssetClassification c : all) {
            AssetClassificationDTO dto = dtoMap.get(c.getId());
            if (AssetClassification.LEVEL_DOMAIN.equals(c.getLevel())) {
                roots.add(dto);
            } else if (c.getParentId() != null && dtoMap.containsKey(c.getParentId())) {
                AssetClassificationDTO parent = dtoMap.get(c.getParentId());
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(dto);
            }
        }

        // 计数回填：一次 GROUP BY 聚合出域/主题/未分类/全部四个口径（与 browse 同为 ONLINE 口径）
        Map<String, Long> domainCounts = new HashMap<>();
        Map<String, Long> topicCounts = new HashMap<>();
        long totalCount = 0;
        long uncategorizedCount = 0;
        for (Map<String, Object> row : metadataTableMapper.countByClassification()) {
            String domain = (String) row.get("data_domain");
            String topic = (String) row.get("data_topic");
            long cnt = ((Number) row.get("cnt")).longValue();
            totalCount += cnt;
            if (domain == null || domain.isBlank()) {
                uncategorizedCount += cnt;
                continue;
            }
            domainCounts.merge(domain, cnt, Long::sum);
            if (topic != null && !topic.isBlank()) {
                topicCounts.merge(domain + "\0" + topic, cnt, Long::sum);
            }
        }
        for (AssetClassificationDTO domainDto : roots) {
            domainDto.setTableCount(domainCounts.getOrDefault(domainDto.getName(), 0L));
            if (domainDto.getChildren() != null) {
                for (AssetClassificationDTO topicDto : domainDto.getChildren()) {
                    topicDto.setTableCount(topicCounts.getOrDefault(
                            domainDto.getName() + "\0" + topicDto.getName(), 0L));
                }
            }
        }

        AssetClassificationTreeDTO result = new AssetClassificationTreeDTO();
        result.setList(roots);
        result.setTotalCount(totalCount);
        result.setUncategorizedCount(uncategorizedCount);
        return result;
    }

    /** 新增分类（仅写 created_by/created_at，审计约定）。 */
    public AssetClassificationDTO createClassification(ClassificationSaveRequest request) {
        validateClassification(request, null);
        AssetClassification entity = new AssetClassification();
        entity.setLevel(request.getLevel());
        entity.setName(request.getName().trim());
        entity.setParentId(request.getParentId());
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
        entity.setCreatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        classificationMapper.insert(entity);
        return toClassificationDTO(entity);
    }

    /**
     * 编辑分类。改名时级联更新 metadata_table 冗余的 data_domain/data_topic（冗余存名设计，用户确认）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AssetClassificationDTO updateClassification(Long id, ClassificationSaveRequest request) {
        AssetClassification existing = classificationMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_NOT_FOUND);
        }
        validateClassification(request, id);

        String oldName = existing.getName();
        String newName = request.getName().trim();
        existing.setLevel(request.getLevel());
        existing.setName(newName);
        existing.setParentId(request.getParentId());
        existing.setSort(request.getSort() == null ? 0 : request.getSort());
        existing.setUpdatedBy(currentUserId());
        existing.setUpdatedAt(LocalDateTime.now());
        classificationMapper.updateById(existing);

        // 级联更新冗余名称（仅改名时）
        if (!Objects.equals(oldName, newName)) {
            String column = AssetClassification.LEVEL_DOMAIN.equals(existing.getLevel())
                    ? "data_domain" : "data_topic";
            UpdateWrapper<MetadataTable> wrapper = new UpdateWrapper<>();
            wrapper.eq(column, oldName).set(column, newName);
            int updated = metadataTableMapper.update(null, wrapper);
            if (updated > 0) {
                log.info("分类改名级联更新 metadata_table.{}：{} → {}（{} 张表）", column, oldName, newName, updated);
            }
        }
        return toClassificationDTO(existing);
    }

    /**
     * 删除分类。校验：DOMAIN 下仍有 TOPIC 不可删；仍被 metadata_table 引用不可删（PRD §7）。
     */
    public void deleteClassification(Long id) {
        AssetClassification existing = classificationMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_NOT_FOUND);
        }
        if (AssetClassification.LEVEL_DOMAIN.equals(existing.getLevel())) {
            Long children = classificationMapper.selectCount(new QueryWrapper<AssetClassification>()
                    .eq("parent_id", id));
            if (children != null && children > 0) {
                throw new BusinessException(ErrorCode.CLASSIFICATION_IN_USE, "该数据域下仍有主题分类，请先删除子分类");
            }
        }
        String column = AssetClassification.LEVEL_DOMAIN.equals(existing.getLevel())
                ? "data_domain" : "data_topic";
        Long refs = metadataTableMapper.selectCount(new QueryWrapper<MetadataTable>()
                .eq(column, existing.getName()));
        if (refs != null && refs > 0) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_IN_USE,
                    "仍有 " + refs + " 张表引用该分类，请先解除分配");
        }
        classificationMapper.deleteById(id);
    }

    /** 分类新增/编辑公共校验：level 枚举、名称非空、TOPIC 父分类合法、同级不重名。 */
    private void validateClassification(ClassificationSaveRequest request, Long excludeId) {
        if (request == null || request.getLevel() == null
                || (!AssetClassification.LEVEL_DOMAIN.equals(request.getLevel())
                && !AssetClassification.LEVEL_TOPIC.equals(request.getLevel()))) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_PARENT_INVALID, "层级必须为 DOMAIN 或 TOPIC");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_PARENT_INVALID, "分类名称不能为空");
        }
        if (AssetClassification.LEVEL_DOMAIN.equals(request.getLevel())) {
            if (request.getParentId() != null) {
                throw new BusinessException(ErrorCode.CLASSIFICATION_PARENT_INVALID, "数据域（一级）不能有父分类");
            }
        } else {
            if (request.getParentId() == null) {
                throw new BusinessException(ErrorCode.CLASSIFICATION_PARENT_INVALID, "主题（二级）必须挂在数据域下");
            }
            AssetClassification parent = classificationMapper.selectById(request.getParentId());
            if (parent == null || !AssetClassification.LEVEL_DOMAIN.equals(parent.getLevel())) {
                throw new BusinessException(ErrorCode.CLASSIFICATION_PARENT_INVALID);
            }
        }
        QueryWrapper<AssetClassification> dup = new QueryWrapper<AssetClassification>()
                .eq("level", request.getLevel())
                .eq("name", request.getName().trim());
        if (excludeId != null) {
            dup.ne("id", excludeId);
        }
        Long count = classificationMapper.selectCount(dup);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_NAME_EXISTS);
        }
    }

    // ==================== DC-05 分类浏览 ====================

    /**
     * 分类浏览：按数据域/主题/数据源/健康度筛选 ONLINE 表分页返回，回填质量分与负责人名。
     * uncategorized=true 时查未分类（data_domain 为空）的表；sort=score 按质量分降序（内存排序，封顶 1000）。
     */
    public PageResult<AssetSearchItemDTO> browse(String domain, String topic, Long datasourceId,
                                                 String healthLevel, boolean uncategorized, String sort,
                                                 int page, int pageSize) {
        // 健康度筛选：质量分在另一张表，先反查表 ID 集合再拼 IN（无命中直接返回空页）
        List<Long> healthTableIds = null;
        if (healthLevel != null && !healthLevel.isBlank()) {
            healthTableIds = qualityScoreService.findTableIdsByHealthLevel(healthLevel);
            if (healthTableIds.isEmpty()) {
                return new PageResult<>(List.of(), 0, page, pageSize);
            }
        }
        QueryWrapper<MetadataTable> wrapper = buildBrowseWrapper(domain, topic, datasourceId, uncategorized);
        if (healthTableIds != null) {
            wrapper.in("id", healthTableIds);
        }

        if ("score".equalsIgnoreCase(sort)) {
            // 质量分在另一张表，先拉全量（封顶）回填后内存排序再手工分页
            List<MetadataTable> all = metadataTableMapper.selectList(
                    wrapper.orderByAsc("table_name").last("LIMIT " + MAX_BROWSABLE_ROWS));
            List<AssetSearchItemDTO> items = all.stream()
                    .map(t -> toItemDTO(t, null))
                    .collect(Collectors.toCollection(ArrayList::new));
            backfill(items);
            items.sort(Comparator.comparing(AssetSearchItemDTO::getQualityScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            int from = Math.min((page - 1) * pageSize, items.size());
            int to = Math.min(from + pageSize, items.size());
            return new PageResult<>(new ArrayList<>(items.subList(from, to)), items.size(), page, pageSize);
        }

        wrapper.orderByAsc("table_name");
        IPage<MetadataTable> mpPage = metadataTableMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<AssetSearchItemDTO> items = mpPage.getRecords().stream()
                .map(t -> toItemDTO(t, null))
                .collect(Collectors.toCollection(ArrayList::new));
        backfill(items);
        return new PageResult<>(items, mpPage.getTotal(), mpPage.getCurrent(), mpPage.getSize());
    }

    private QueryWrapper<MetadataTable> buildBrowseWrapper(String domain, String topic,
                                                           Long datasourceId, boolean uncategorized) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("source_status", "ONLINE");
        if (uncategorized) {
            wrapper.and(w -> w.isNull("data_domain").or().eq("data_domain", ""));
        } else {
            if (domain != null && !domain.isBlank()) {
                wrapper.eq("data_domain", domain);
            }
            if (topic != null && !topic.isBlank()) {
                wrapper.eq("data_topic", topic);
            }
        }
        if (datasourceId != null) {
            wrapper.eq("datasource_id", datasourceId);
        }
        return wrapper;
    }

    // ==================== DC-02 分配分类 / 负责人 ====================

    /**
     * 为表分配分类（或传空清除）。校验：分类必须存在于 asset_classification，主题须属于该数据域。
     */
    public void assignClassification(Long tableId, AssignClassificationRequest request) {
        MetadataTable table = metadataTableMapper.selectById(tableId);
        if (table == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        String[] normalized = normalizeAndValidateClassification(
                request == null ? null : request.getDataDomain(),
                request == null ? null : request.getDataTopic());
        UpdateWrapper<MetadataTable> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", tableId)
                .set("data_domain", normalized[0])
                .set("data_topic", normalized[1])
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        metadataTableMapper.update(null, wrapper);
    }

    /**
     * 批量分配分类（Sprint 7 F1 修订）：一次校验 + 一条 UPDATE ... IN，替代前端循环单表调用。
     * 返回实际更新的表数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int assignClassificationBatch(AssignClassificationBatchRequest request) {
        if (request == null || request.getTableIds() == null || request.getTableIds().isEmpty()) {
            return 0;
        }
        String[] normalized = normalizeAndValidateClassification(request.getDataDomain(), request.getDataTopic());
        UpdateWrapper<MetadataTable> wrapper = new UpdateWrapper<>();
        wrapper.in("id", request.getTableIds())
                .set("data_domain", normalized[0])
                .set("data_topic", normalized[1])
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        return metadataTableMapper.update(null, wrapper);
    }

    /** 分配分类的公共校验与规整：返回 [domain, topic]（均已 trim，空为 null）。 */
    private String[] normalizeAndValidateClassification(String rawDomain, String rawTopic) {
        String domain = trimToNull(rawDomain);
        String topic = trimToNull(rawTopic);

        if (topic != null && domain == null) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_PARENT_INVALID, "分配主题前必须先指定数据域");
        }
        if (domain != null) {
            AssetClassification domainClass = findClassification(AssetClassification.LEVEL_DOMAIN, domain, null);
            if (domainClass == null) {
                throw new BusinessException(ErrorCode.CLASSIFICATION_NOT_FOUND, "数据域不存在：" + domain);
            }
            if (topic != null) {
                AssetClassification topicClass = findClassification(AssetClassification.LEVEL_TOPIC, topic,
                        domainClass.getId());
                if (topicClass == null) {
                    throw new BusinessException(ErrorCode.CLASSIFICATION_NOT_FOUND,
                            "主题不存在或不属于该数据域：" + topic);
                }
            }
        }
        return new String[]{domain, topic};
    }

    /** 为表配置负责人（或传 null 清除）。校验用户存在。 */
    public void assignOwner(Long tableId, AssignOwnerRequest request) {
        MetadataTable table = metadataTableMapper.selectById(tableId);
        if (table == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        Long ownerUserId = request == null ? null : request.getOwnerUserId();
        if (ownerUserId != null) {
            // 经 system 服务校验负责人用户存在（只用到 username 映射的 key）
            if (!usernames(List.of(ownerUserId)).containsKey(ownerUserId)) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND, "负责人用户不存在：" + ownerUserId);
            }
        }
        UpdateWrapper<MetadataTable> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", tableId)
                .set("owner_user_id", ownerUserId)
                .set("updated_by", currentUserId())
                .set("updated_at", LocalDateTime.now());
        metadataTableMapper.update(null, wrapper);
    }

    private AssetClassification findClassification(String level, String name, Long parentId) {
        QueryWrapper<AssetClassification> wrapper = new QueryWrapper<AssetClassification>()
                .eq("level", level)
                .eq("name", name)
                .last("limit 1");
        if (parentId != null) {
            wrapper.eq("parent_id", parentId);
        }
        return classificationMapper.selectOne(wrapper);
    }

    // ==================== private ====================

    /** 批量回填：质量分/健康度（mapByTableIds 一次 IN 查询）+ 负责人名 + 数据源名称/类型（browse 无 join 时补齐）。 */
    private void backfill(List<AssetSearchItemDTO> items) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> tableIds = items.stream().map(AssetSearchItemDTO::getTableId).toList();
        Map<Long, QualityScoreDTO> scoreMap = qualityScoreService.mapByTableIds(tableIds);
        Map<Long, String> usernameMap = usernames(
                items.stream().map(AssetSearchItemDTO::getOwnerUserId).filter(Objects::nonNull).toList());
        List<Long> dsIds = items.stream().map(AssetSearchItemDTO::getDatasourceId)
                .filter(Objects::nonNull).distinct().toList();
        // 经 engineering 服务 Feign 批量回填数据源名/类型；失败经 RemoteCalls 降级为空 Map（名称列退化），不阻断搜索
        Map<Long, DataSourceInfo> dsMap = dsIds.isEmpty()
                ? Map.of()
                : RemoteCalls.execute("engineering.datasource.batchGet", () -> {
                    IdsRequest request = new IdsRequest();
                    request.setIds(dsIds);
                    Result<Map<Long, DataSourceInfo>> result = datasourceApi.batchGet(request);
                    return result == null || result.data() == null ? Map.<Long, DataSourceInfo>of() : result.data();
                }, Map.of());
        for (AssetSearchItemDTO item : items) {
            QualityScoreDTO score = scoreMap.get(item.getTableId());
            if (score != null) {
                item.setQualityScore(score.getScore());
                item.setHealthLevel(score.getHealthLevel());
            }
            if (item.getOwnerUserId() != null) {
                item.setOwnerName(usernameMap.get(item.getOwnerUserId()));
            }
            DataSourceInfo ds = dsMap.get(item.getDatasourceId());
            if (ds != null) {
                item.setDatasourceName(ds.getName());
                item.setDatasourceType(ds.getType());
            }
        }
    }

    private AssetSearchItemDTO toItemDTO(MetadataTable t, Integer score) {
        AssetSearchItemDTO dto = new AssetSearchItemDTO();
        dto.setTableId(t.getId());
        dto.setTableName(t.getTableName());
        dto.setTableComment(t.getTableComment() != null ? t.getTableComment() : t.getManualComment());
        dto.setDatabaseName(t.getDatabaseName());
        dto.setSchemaName(t.getSchemaName());
        dto.setDatasourceId(t.getDatasourceId());
        dto.setDatasourceName(t.getDatasourceName());
        dto.setDatasourceType(t.getDatasourceType());
        // 内置 Doris（伪 datasource_id=-1）在 engineering 查不到连接，按 source_type 直接回显
        // 「Doris 数仓 / DORIS」（对齐 MetadataService 数据源列表口径）
        if (SourceType.BUILTIN_DORIS.getCode().equals(t.getSourceType())) {
            dto.setDatasourceName("Doris 数仓");
            dto.setDatasourceType(DataSourceType.DORIS.getCode());
        }
        dto.setDataDomain(t.getDataDomain());
        dto.setDataTopic(t.getDataTopic());
        dto.setOwnerUserId(t.getOwnerUserId());
        dto.setScore(score);
        dto.setUpdatedAt(t.getUpdatedAt());
        return dto;
    }

    private AssetClassificationDTO toClassificationDTO(AssetClassification entity) {
        AssetClassificationDTO dto = new AssetClassificationDTO();
        dto.setId(entity.getId());
        dto.setLevel(entity.getLevel());
        dto.setName(entity.getName());
        dto.setParentId(entity.getParentId());
        dto.setSort(entity.getSort());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
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

    /**
     * 经 system 服务 Feign 批量查询 userId → username 映射。
     * system 不可用时降级为空 Map 并记 warn（名称列退化为空），不拖垮本接口。
     */
    private Map<Long, String> usernames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常（如序列化错），warn + 计数后返回空 Map
        return RemoteCalls.execute("system.usernames", () -> {
            Result<Map<Long, String>> result = systemUserApi.usernames(userIds.stream().toList());
            return result == null || result.data() == null ? Map.of() : result.data();
        }, Map.of());
    }

    /**
     * 经 system 服务 Feign 按用户名模糊查询 userId 列表。
     * system 不可用时降级为空列表并记 warn（负责人维度搜索退化为无命中），不拖垮资产搜索。
     */
    private List<Long> findUserIdsByNameKeyword(String keyword) {
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常，warn + 计数后返回空列表
        return RemoteCalls.execute("system.findUserIdsByNameKeyword", () -> {
            Result<List<Long>> result = systemUserApi.findUserIdsByNameKeyword(keyword);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
    }
}
