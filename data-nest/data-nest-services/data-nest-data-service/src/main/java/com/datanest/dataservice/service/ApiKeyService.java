package com.datanest.dataservice.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.dataservice.dto.ApiKeyCreateRequest;
import com.datanest.dataservice.dto.ApiKeyCreateResult;
import com.datanest.dataservice.dto.ApiKeyDetailDTO;
import com.datanest.dataservice.dto.ApiKeyPageItem;
import com.datanest.dataservice.dto.ApiKeyUpdateRequest;
import com.datanest.dataservice.dto.RefCount;
import com.datanest.dataservice.entity.ApiKey;
import com.datanest.dataservice.entity.ApiKeyBinding;
import com.datanest.dataservice.entity.ApiKeyPipeline;
import com.datanest.dataservice.entity.DataApi;
import com.datanest.dataservice.mapper.ApiCallLogMapper;
import com.datanest.dataservice.mapper.ApiKeyBindingMapper;
import com.datanest.dataservice.mapper.ApiKeyMapper;
import com.datanest.dataservice.mapper.ApiKeyPipelineMapper;
import com.datanest.dataservice.mapper.DataApiMapper;
import com.datanest.system.api.SystemUserApi;
import com.datanest.task.core.support.SystemUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * API Key 服务（Sprint 10 F2）：创建（明文仅返回一次，SHA-256 哈希落库）/ 编辑 / 快捷启停 / 删除 / 绑定 API。
 * <p>
 * 列表含「绑定 API 数」与「近 7 天调用」聚合（0 = 僵尸 Key，PRD 6.4 建议停用防泄露）。
 */
@Service
public class ApiKeyService {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyService.class);

    /** Key 明文前缀（PRD 6.4，K-xxxx 形态） */
    private static final String KEY_PREFIX = "K-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyBindingMapper bindingMapper;
    private final ApiKeyPipelineMapper pipelineMapper;
    private final ApiCallLogMapper callLogMapper;
    private final DataApiMapper dataApiMapper;
    private final SystemUserApi systemUserApi;

    public ApiKeyService(ApiKeyMapper apiKeyMapper,
                         ApiKeyBindingMapper bindingMapper,
                         ApiKeyPipelineMapper pipelineMapper,
                         ApiCallLogMapper callLogMapper,
                         DataApiMapper dataApiMapper,
                         SystemUserApi systemUserApi) {
        this.apiKeyMapper = apiKeyMapper;
        this.bindingMapper = bindingMapper;
        this.pipelineMapper = pipelineMapper;
        this.callLogMapper = callLogMapper;
        this.dataApiMapper = dataApiMapper;
        this.systemUserApi = systemUserApi;
    }

    /**
     * 创建 Key：生成一次性明文（K- + 32 hex），只存 SHA-256 哈希；可同时绑定 API。
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiKeyCreateResult create(ApiKeyCreateRequest request) {
        assertNameAvailable(request.getName(), null);
        List<Long> apiIds = validateApiIds(request.getApiIds());

        String plainKey = generateKey();
        ApiKey key = new ApiKey();
        key.setName(request.getName().trim());
        key.setKeyHash(sha256Hex(plainKey));
        key.setQpsLimit(request.getQpsLimit());
        key.setStatus(ApiKey.STATUS_ENABLED);
        key.setCreatedBy(currentUserId());
        key.setCreatedAt(LocalDateTime.now());
        apiKeyMapper.insert(key);
        replaceBindings(key.getId(), apiIds);
        logger.info("创建 API Key: id={}, name={}, boundApis={}", key.getId(), key.getName(), apiIds.size());

        ApiKeyCreateResult result = new ApiKeyCreateResult();
        result.setId(key.getId());
        result.setName(key.getName());
        result.setApiKey(plainKey);
        result.setQpsLimit(key.getQpsLimit());
        result.setStatus(key.getStatus());
        result.setCreatedAt(key.getCreatedAt());
        return result;
    }

    /**
     * 编辑 Key：改名 / 限流 QPS / 全量重绑 API。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ApiKeyUpdateRequest request) {
        ApiKey key = loadKey(id);
        assertNameAvailable(request.getName(), id);
        List<Long> apiIds = validateApiIds(request.getApiIds());

        key.setName(request.getName().trim());
        key.setQpsLimit(request.getQpsLimit());
        key.setUpdatedBy(currentUserId());
        key.setUpdatedAt(LocalDateTime.now());
        apiKeyMapper.updateById(key);
        replaceBindings(id, apiIds);
        logger.info("编辑 API Key: id={}, name={}, boundApis={}", id, key.getName(), apiIds.size());
    }

    /**
     * 快捷启用（操作列一步恢复）。
     */
    public void enable(Long id) {
        ApiKey key = loadKey(id);
        if (ApiKey.STATUS_ENABLED.equals(key.getStatus())) {
            return;
        }
        key.setStatus(ApiKey.STATUS_ENABLED);
        key.setUpdatedBy(currentUserId());
        key.setUpdatedAt(LocalDateTime.now());
        apiKeyMapper.updateById(key);
        logger.info("启用 API Key: id={}, name={}", id, key.getName());
    }

    /**
     * 快捷禁用（泄露 1 步处置）；禁用后对外调用立即 401。
     */
    public void disable(Long id) {
        ApiKey key = loadKey(id);
        if (ApiKey.STATUS_DISABLED.equals(key.getStatus())) {
            return;
        }
        key.setStatus(ApiKey.STATUS_DISABLED);
        key.setUpdatedBy(currentUserId());
        key.setUpdatedAt(LocalDateTime.now());
        apiKeyMapper.updateById(key);
        logger.info("禁用 API Key: id={}, name={}", id, key.getName());
    }

    /**
     * 删除 Key：同时清理 API 绑定与管道订阅授权。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ApiKey key = loadKey(id);
        bindingMapper.delete(new QueryWrapper<ApiKeyBinding>().eq("key_id", id));
        pipelineMapper.delete(new QueryWrapper<ApiKeyPipeline>().eq("key_id", id));
        apiKeyMapper.deleteById(id);
        logger.info("删除 API Key: id={}, name={}", id, key.getName());
    }

    /**
     * 分页列表：keyword 匹配名称；status 精确过滤；含绑定 API 数 + 近 7 天调用聚合。
     */
    public PageResult<ApiKeyPageItem> page(long page, long pageSize, String keyword, String status) {
        QueryWrapper<ApiKey> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like("name", keyword.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status.trim());
        }
        wrapper.orderByDesc("created_at");
        Page<ApiKey> p = apiKeyMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100)), wrapper);

        List<ApiKey> records = p.getRecords();
        List<Long> keyIds = records.stream().map(ApiKey::getId).toList();
        Map<Long, Long> boundApiCounts = keyIds.isEmpty() ? Map.of()
                : countMap(bindingMapper.countApisByKeyIds(keyIds));
        Map<Long, Long> calls7d = keyIds.isEmpty() ? Map.of()
                : countMap(callLogMapper.countCallsByKeyIdsSince(keyIds, LocalDateTime.now().minusDays(7)));
        Map<Long, String> usernames = SystemUserResolver.usernames(systemUserApi,
                records.stream().flatMap(key -> java.util.stream.Stream.of(key.getCreatedBy(), key.getUpdatedBy()))
                        .filter(Objects::nonNull).distinct().toList());

        List<ApiKeyPageItem> items = records.stream().map(key -> {
            ApiKeyPageItem item = new ApiKeyPageItem();
            item.setId(key.getId());
            item.setName(key.getName());
            item.setStatus(key.getStatus());
            item.setQpsLimit(key.getQpsLimit());
            item.setBoundApiCount(boundApiCounts.getOrDefault(key.getId(), 0L));
            item.setCalls7d(calls7d.getOrDefault(key.getId(), 0L));
            item.setCreatedBy(key.getCreatedBy());
            item.setCreatedByName(usernames.get(key.getCreatedBy()));
            item.setCreatedAt(key.getCreatedAt());
            item.setUpdatedByName(usernames.get(key.getUpdatedBy()));
            item.setUpdatedAt(key.getUpdatedAt());
            return item;
        }).toList();
        return PageResult.of(items, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * Key 详情：编辑弹窗预填用（当前绑定 apiIds）；明文 Key 只在创建时返回，详情不含。
     */
    public ApiKeyDetailDTO detail(Long id) {
        ApiKey key = loadKey(id);
        ApiKeyDetailDTO dto = new ApiKeyDetailDTO();
        dto.setId(key.getId());
        dto.setName(key.getName());
        dto.setStatus(key.getStatus());
        dto.setQpsLimit(key.getQpsLimit());
        dto.setApiIds(bindingMapper.selectList(new QueryWrapper<ApiKeyBinding>().eq("key_id", id))
                .stream().map(ApiKeyBinding::getApiId).toList());
        Map<Long, String> usernames = SystemUserResolver.usernames(systemUserApi,
                java.util.stream.Stream.of(key.getCreatedBy(), key.getUpdatedBy())
                        .filter(Objects::nonNull).distinct().toList());
        dto.setCreatedByName(usernames.get(key.getCreatedBy()));
        dto.setCreatedAt(key.getCreatedAt());
        dto.setUpdatedAt(key.getUpdatedAt());
        return dto;
    }

    // ---------- 内部方法 ----------

    /** 加载 Key，查无抛 9014 */
    private ApiKey loadKey(Long id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key == null) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }
        return key;
    }

    /** 名称查重（excludeId 用于编辑时排除自身） */
    private void assertNameAvailable(String name, Long excludeId) {
        QueryWrapper<ApiKey> wrapper = new QueryWrapper<ApiKey>().eq("name", name.trim());
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (apiKeyMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.API_KEY_NAME_EXISTS, "Key 名称已存在: " + name.trim());
        }
    }

    /** 校验绑定 API 均存在且未删除；null 视为空列表 */
    private List<Long> validateApiIds(List<Long> apiIds) {
        if (apiIds == null || apiIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = apiIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return List.of();
        }
        Long validCount = dataApiMapper.selectCount(new QueryWrapper<DataApi>()
                .in("id", distinctIds).eq("deleted", 0));
        if (validCount != distinctIds.size()) {
            throw new BusinessException(ErrorCode.API_NOT_FOUND, "绑定的 API 不存在或已删除");
        }
        return distinctIds;
    }

    /** 全量重绑：先删后插（绑定关系无审计字段，直接替换） */
    private void replaceBindings(Long keyId, List<Long> apiIds) {
        bindingMapper.delete(new QueryWrapper<ApiKeyBinding>().eq("key_id", keyId));
        for (Long apiId : apiIds) {
            ApiKeyBinding binding = new ApiKeyBinding();
            binding.setKeyId(keyId);
            binding.setApiId(apiId);
            binding.setCreatedAt(LocalDateTime.now());
            bindingMapper.insert(binding);
        }
    }

    /** 生成 Key 明文：K- + 32 位小写 hex（16 字节安全随机） */
    private String generateKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 hex（key_hash 落库，明文不留痕）；static 供 OpenApiKeyFilter 复用 */
    public static String sha256Hex(String plainKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Map<Long, Long> countMap(List<RefCount> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        return counts.stream().collect(Collectors.toMap(RefCount::getRefId, RefCount::getCnt));
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
