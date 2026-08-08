package com.datanest.engineering.service;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.CreateTaskResultDTO;
import com.datanest.engineering.dto.SyncJobCreateRequest;
import com.datanest.engineering.dto.TaskTemplateDTO;
import com.datanest.engineering.dto.TaskTemplateQueryRequest;
import com.datanest.engineering.dto.TaskTemplateSaveRequest;
import com.datanest.engineering.dto.TemplateCreateTaskRequest;
import com.datanest.engineering.entity.SyncJob;
import com.datanest.engineering.entity.TaskTemplate;
import com.datanest.engineering.mapper.SyncJobMapper;
import com.datanest.engineering.mapper.TaskTemplateMapper;
import com.datanest.governance.api.CollectWriteApi;
import com.datanest.governance.api.dto.CollectTaskCreateInternalRequest;
import com.datanest.governance.api.dto.CollectTaskInfoDTO;
import com.datanest.system.api.SystemUserApi;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 任务模板库（Sprint 7 DD-09）。
 * <p>
 * 范围（用户确认 2026-08-08）：仅 SYNC / COLLECT 两类。SYNC 一键创建本地落 sync_job；
 * COLLECT 经 governance-api 内部端点远程创建 collect_task（写操作 fail-closed，异常传播）。
 * 模板为快照式：删除模板不影响已创建任务，任务也不回引模板，故删除无引用校验。
 */
@Service
public class TaskTemplateService {

    private static final Logger logger = LoggerFactory.getLogger(TaskTemplateService.class);

    /** 占位符 token：{key}，key 为字母/数字/下划线 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private static final Set<String> SUPPORTED_TYPES = Set.of(TaskTemplate.TYPE_SYNC, TaskTemplate.TYPE_COLLECT);

    private final TaskTemplateMapper taskTemplateMapper;
    private final SyncJobMapper syncJobMapper;
    private final SyncJobService syncJobService;
    private final CollectWriteApi collectWriteApi;
    private final SystemUserApi systemUserApi;
    private final Validator validator;

    public TaskTemplateService(TaskTemplateMapper taskTemplateMapper, SyncJobMapper syncJobMapper,
                               SyncJobService syncJobService, CollectWriteApi collectWriteApi,
                               SystemUserApi systemUserApi, Validator validator) {
        this.taskTemplateMapper = taskTemplateMapper;
        this.syncJobMapper = syncJobMapper;
        this.syncJobService = syncJobService;
        this.collectWriteApi = collectWriteApi;
        this.systemUserApi = systemUserApi;
        this.validator = validator;
    }

    /** 模板列表（含内置），按 type/category 可选过滤；createdByName 批量回填（降级空 Map）。 */
    public List<TaskTemplateDTO> list(String type, String category) {
        LambdaQueryWrapper<TaskTemplate> wrapper = new LambdaQueryWrapper<TaskTemplate>()
                .eq(type != null && !type.isBlank(), TaskTemplate::getType, type)
                .eq(category != null && !category.isBlank(), TaskTemplate::getCategory, category)
                .orderByAsc(TaskTemplate::getCategory)
                .orderByDesc(TaskTemplate::getCreatedAt);
        List<TaskTemplate> templates = taskTemplateMapper.selectList(wrapper);
        return attachCreatedByName(templates);
    }

    /** 模板分页（对齐平台列表页 POST /page 约定；排序与 list 一致：内置在前 + 创建时间倒序）。 */
    public PageResult<TaskTemplateDTO> listPage(TaskTemplateQueryRequest request) {
        int page = request.getPage() == null || request.getPage() < 1 ? 1 : request.getPage();
        int pageSize = request.getPageSize() == null || request.getPageSize() < 1 ? 10 : request.getPageSize();
        LambdaQueryWrapper<TaskTemplate> wrapper = new LambdaQueryWrapper<TaskTemplate>()
                .eq(request.getType() != null && !request.getType().isBlank(),
                        TaskTemplate::getType, request.getType())
                .eq(request.getCategory() != null && !request.getCategory().isBlank(),
                        TaskTemplate::getCategory, request.getCategory())
                .orderByAsc(TaskTemplate::getCategory)
                .orderByDesc(TaskTemplate::getCreatedAt);
        Page<TaskTemplate> mpPage = taskTemplateMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<TaskTemplateDTO> items = attachCreatedByName(mpPage.getRecords());
        return new PageResult<>(items, mpPage.getTotal(), mpPage.getCurrent(), mpPage.getSize());
    }

    /** createdByName 批量回填（经 system-api，降级空 Map）；内置模板 createdBy 为 null，前端展示「系统」。 */
    private List<TaskTemplateDTO> attachCreatedByName(List<TaskTemplate> templates) {
        List<Long> userIds = templates.stream().map(TaskTemplate::getCreatedBy)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常；Result 非 200 返回 null data 时也按空 Map 处理
        Map<Long, String> usernameMap = userIds.isEmpty() ? Collections.emptyMap()
                : RemoteCalls.execute("system.usernames", () -> {
                    Result<Map<Long, String>> result = systemUserApi.usernames(userIds);
                    return result == null || result.data() == null
                            ? Collections.<Long, String>emptyMap() : result.data();
                }, Collections.emptyMap());

        return templates.stream().map(t -> toDTO(t, usernameMap)).collect(Collectors.toList());
    }

    /** 新增自定义模板（含从既有任务另存为）。 */
    @Transactional
    public TaskTemplateDTO create(TaskTemplateSaveRequest request) {
        validateType(request.getType());
        if (countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_NAME_EXISTS);
        }
        String configTemplate = buildConfigTemplate(request.getType(), request.getConfigTemplate(), request.getSourceTaskId());

        TaskTemplate entity = new TaskTemplate();
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setCategory(TaskTemplate.CATEGORY_CUSTOM);
        entity.setDescription(request.getDescription());
        entity.setConfigTemplate(configTemplate);
        entity.setEnabled(1);
        entity.setCreatedBy(currentUserId());
        entity.setCreatedAt(LocalDateTime.now());
        taskTemplateMapper.insert(entity);
        logger.info("任务模板已创建: id={}, name={}, type={}", entity.getId(), entity.getName(), entity.getType());
        return toDTO(entity, Collections.emptyMap());
    }

    /** 编辑自定义模板（内置禁改；type 不可变）。 */
    @Transactional
    public TaskTemplateDTO update(Long id, TaskTemplateSaveRequest request) {
        TaskTemplate entity = requireTemplate(id);
        if (TaskTemplate.CATEGORY_BUILTIN.equals(entity.getCategory())) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_BUILTIN_READONLY);
        }
        if (!entity.getName().equals(request.getName()) && countByName(request.getName()) > 0) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_NAME_EXISTS);
        }
        if (!entity.getType().equals(request.getType())) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_TYPE_INVALID, "模板类型不可变更");
        }
        // 编辑时 configTemplate 与 sourceTaskId 都缺省 = 仅改名称/说明，保留原配置
        boolean keepConfig = (request.getConfigTemplate() == null || request.getConfigTemplate().isBlank())
                && request.getSourceTaskId() == null;
        String configTemplate = keepConfig ? entity.getConfigTemplate()
                : buildConfigTemplate(entity.getType(), request.getConfigTemplate(), request.getSourceTaskId());

        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setConfigTemplate(configTemplate);
        entity.setUpdatedBy(currentUserId());
        entity.setUpdatedAt(LocalDateTime.now());
        taskTemplateMapper.updateById(entity);
        return toDTO(entity, Collections.emptyMap());
    }

    /** 删除自定义模板（内置禁删；快照式，无引用校验）。 */
    @Transactional
    public void delete(Long id) {
        TaskTemplate entity = requireTemplate(id);
        if (TaskTemplate.CATEGORY_BUILTIN.equals(entity.getCategory())) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_BUILTIN_READONLY);
        }
        taskTemplateMapper.deleteById(id);
        logger.info("任务模板已删除: id={}, name={}", id, entity.getName());
    }

    /**
     * 从模板一键创建任务：校验必填占位符 → 文本替换 → 反序列化为对应类型创建请求 → Bean Validation → 落库。
     */
    public CreateTaskResultDTO createTask(Long id, TemplateCreateTaskRequest request) {
        TaskTemplate template = requireTemplate(id);
        if (template.getEnabled() == null || template.getEnabled() != 1) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CREATE_FAILED, "模板已停用，无法创建任务");
        }
        JSONObject templateJson = parseTemplateJson(template.getConfigTemplate());
        JSONObject config = templateJson.getJSONObject("config");
        if (config == null) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CONFIG_INVALID, "模板缺少 config 节点");
        }
        Map<String, PlaceholderMeta> metas = parsePlaceholders(templateJson.getJSONArray("placeholders"));
        String resolvedConfig = resolvePlaceholders(config.toJSONString(), metas,
                request.getValues() == null ? Collections.emptyMap() : request.getValues());

        if (TaskTemplate.TYPE_SYNC.equals(template.getType())) {
            SyncJobCreateRequest createRequest = parseAndValidate(resolvedConfig, SyncJobCreateRequest.class);
            createRequest.setName(request.getName());
            validateBean(createRequest);
            return new CreateTaskResultDTO(TaskTemplate.TYPE_SYNC, syncJobService.create(createRequest).getId());
        }
        // COLLECT：远程创建采集任务（写操作 fail-closed，异常/空结果直接报错，不降级）
        CollectTaskCreateInternalRequest createRequest = parseAndValidate(resolvedConfig, CollectTaskCreateInternalRequest.class);
        createRequest.setName(request.getName());
        createRequest.setCreatedBy(currentUserId());
        Result<Long> result = collectWriteApi.createTask(createRequest);
        if (result == null || result.data() == null) {
            // 熔断降级或返回异常：业务校验失败时透传 governance 的错误消息，否则给通用提示
            String detail = result != null && result.code() != 200 && result.message() != null
                    ? result.message() : "采集服务创建任务失败，请稍后重试";
            logger.error("远程创建采集任务失败: templateId={}, result={}", id, result);
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CREATE_FAILED, detail);
        }
        return new CreateTaskResultDTO(TaskTemplate.TYPE_COLLECT, result.data());
    }

    // ========== 模板配置构建 ==========

    /** sourceTaskId 非空时从既有任务配置生成（另存为），否则使用原文并校验合法。 */
    private String buildConfigTemplate(String type, String rawConfigTemplate, Long sourceTaskId) {
        String configTemplate = sourceTaskId != null
                ? buildFromTask(type, sourceTaskId)
                : rawConfigTemplate;
        if (configTemplate == null || configTemplate.isBlank()) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CONFIG_INVALID, "模板配置不能为空");
        }
        validateTemplateJson(type, configTemplate);
        return configTemplate;
    }

    /** 从既有任务另存为模板：配置原样保留，单表 SYNC 的源表与 CRON 表达自动占位化。 */
    private String buildFromTask(String type, Long sourceTaskId) {
        JSONObject config = new JSONObject(new LinkedHashMap<>());
        JSONArray placeholders = new JSONArray();
        if (TaskTemplate.TYPE_SYNC.equals(type)) {
            SyncJob job = syncJobMapper.selectById(sourceTaskId);
            if (job == null) {
                throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
            }
            config.put("sourceDatasourceId", job.getSourceDatasourceId());
            config.put("sourceDatabase", job.getSourceDatabase());
            config.put("sourceSchema", job.getSourceSchema());
            List<String> sourceTables = job.getSourceTables();
            if (sourceTables != null && sourceTables.size() == 1) {
                config.put("sourceTables", List.of("{source_table}"));
                placeholders.add(placeholder("source_table", "源表名", true, null));
            } else {
                config.put("sourceTables", sourceTables);
            }
            config.put("syncMode", job.getSyncMode());
            config.put("incrementalField", job.getIncrementalField());
            config.put("triggerType", job.getTriggerType());
            putCronWithPlaceholder(config, placeholders, job.getTriggerType(), job.getCronExpression());
            config.put("targetDatabase", job.getTargetDatabase());
            config.put("targetTable", job.getTargetTable());
            config.put("retryTimes", job.getRetryTimes());
            config.put("retryInterval", job.getRetryInterval());
            config.put("fieldMapping", job.getFieldMapping());
            config.put("sourceTablesDetail", job.getSourceTablesDetail());
            config.put("readRateLimitMbps", job.getReadRateLimitMbps());
            config.put("writeRateLimitRowsPerSecond", job.getWriteRateLimitRowsPerSecond());
            config.put("rateLimitEnabled", job.getRateLimitEnabled() != null && job.getRateLimitEnabled() == 1);
            config.put("description", job.getDescription());
        } else {
            // COLLECT：经内部端点读任务定义（读路径失败按任务不存在处理）
            Result<CollectTaskInfoDTO> result = collectWriteApi.getTask(sourceTaskId);
            CollectTaskInfoDTO task = result == null ? null : result.data();
            if (task == null) {
                throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
            }
            config.put("datasourceId", task.getDatasourceId());
            config.put("scope", task.getScope());
            config.put("collectMode", task.getCollectMode());
            config.put("triggerType", task.getTriggerType());
            putCronWithPlaceholder(config, placeholders, task.getTriggerType(), task.getCronExpression());
            config.put("description", task.getDescription());
        }
        JSONObject templateJson = new JSONObject(new LinkedHashMap<>());
        templateJson.put("placeholders", placeholders);
        templateJson.put("config", config);
        return templateJson.toJSONString();
    }

    /** CRON 任务的 cronExpression 自动占位化（非必填，默认值为原 cron）。 */
    private void putCronWithPlaceholder(JSONObject config, JSONArray placeholders, String triggerType, String cronExpression) {
        if ("CRON".equalsIgnoreCase(triggerType) && cronExpression != null && !cronExpression.isBlank()) {
            config.put("cronExpression", "{schedule_cron}");
            placeholders.add(placeholder("schedule_cron", "调度 Cron", false, cronExpression));
        } else {
            config.put("cronExpression", cronExpression);
        }
    }

    private JSONObject placeholder(String key, String label, boolean required, String defaultValue) {
        JSONObject p = new JSONObject(new LinkedHashMap<>());
        p.put("key", key);
        p.put("label", label);
        p.put("required", required);
        p.put("valueType", "TEXT");
        if (defaultValue != null) {
            p.put("defaultValue", defaultValue);
        }
        return p;
    }

    // ========== 模板校验与占位符解析 ==========

    /** 保存时校验：JSON 可解析、config 可反序列化为对应类型创建请求。 */
    private void validateTemplateJson(String type, String configTemplate) {
        JSONObject templateJson;
        try {
            templateJson = parseTemplateJson(configTemplate);
        } catch (BusinessException e) {
            throw e;
        }
        JSONObject config = templateJson.getJSONObject("config");
        if (config == null) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CONFIG_INVALID, "模板缺少 config 节点");
        }
        // 占位符原样保留时数值字段（如 datasourceId）无法反序列化，先以哑值替换再校验结构可解析
        String dummyResolved = PLACEHOLDER_PATTERN.matcher(config.toJSONString()).replaceAll("1");
        Class<?> requestClass = TaskTemplate.TYPE_SYNC.equals(type)
                ? SyncJobCreateRequest.class : CollectTaskCreateInternalRequest.class;
        parseAndValidate(dummyResolved, requestClass);
    }

    private JSONObject parseTemplateJson(String configTemplate) {
        try {
            JSONObject json = JSON.parseObject(configTemplate);
            if (json == null) {
                throw new JSONException("empty");
            }
            return json;
        } catch (JSONException e) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CONFIG_INVALID, "模板配置不是合法 JSON");
        }
    }

    private Map<String, PlaceholderMeta> parsePlaceholders(JSONArray array) {
        Map<String, PlaceholderMeta> metas = new LinkedHashMap<>();
        if (array == null) {
            return metas;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject p = array.getJSONObject(i);
            if (p == null || p.getString("key") == null) {
                continue;
            }
            PlaceholderMeta meta = new PlaceholderMeta();
            meta.required = p.getBooleanValue("required");
            meta.defaultValue = p.getString("defaultValue");
            metas.put(p.getString("key"), meta);
        }
        return metas;
    }

    /**
     * 占位符替换：config 中出现的每个 token 都必须有值（用户填写优先，其次模板默认值），否则 7305。
     */
    private String resolvePlaceholders(String configJson, Map<String, PlaceholderMeta> metas, Map<String, String> values) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(configJson);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        Map<String, String> resolved = new HashMap<>();
        for (String token : tokens) {
            String value = values.get(token);
            if (value == null || value.isBlank()) {
                PlaceholderMeta meta = metas.get(token);
                value = meta == null ? null : meta.defaultValue;
            }
            if (value == null || value.isBlank()) {
                throw new BusinessException(ErrorCode.TASK_TEMPLATE_PLACEHOLDER_MISSING, "占位符 {" + token + "} 未填充");
            }
            resolved.put(token, value);
        }
        String result = configJson;
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            // JSON 字符串值内替换，取值中的引号/反斜杠需转义，避免破坏 JSON 结构
            String escaped = entry.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
            result = result.replace("{" + entry.getKey() + "}", escaped);
        }
        return result;
    }

    private <T> T parseAndValidate(String json, Class<T> clazz) {
        try {
            T bean = JSON.parseObject(json, clazz);
            if (bean == null) {
                throw new JSONException("empty");
            }
            return bean;
        } catch (JSONException e) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CONFIG_INVALID, "模板配置解析失败: " + e.getMessage());
        }
    }

    /** 手动触发 Bean Validation（service 内组装的请求不经过 MVC 校验层）。 */
    private <T> void validateBean(T bean) {
        Set<? extends ConstraintViolation<?>> violations = validator.validate(bean);
        if (!violations.isEmpty()) {
            String message = violations.iterator().next().getMessage();
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_CONFIG_INVALID, message);
        }
    }

    // ========== 通用 ==========

    private void validateType(String type) {
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_TYPE_INVALID);
        }
    }

    private TaskTemplate requireTemplate(Long id) {
        TaskTemplate entity = taskTemplateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.TASK_TEMPLATE_NOT_FOUND);
        }
        return entity;
    }

    private long countByName(String name) {
        return taskTemplateMapper.selectCount(
                new LambdaQueryWrapper<TaskTemplate>().eq(TaskTemplate::getName, name));
    }

    private TaskTemplateDTO toDTO(TaskTemplate entity, Map<Long, String> usernameMap) {
        TaskTemplateDTO dto = new TaskTemplateDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setCreatedByName(entity.getCreatedBy() == null ? null : usernameMap.get(entity.getCreatedBy()));
        return dto;
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 占位符元数据（仅解析 required/defaultValue，label/valueType 供前端渲染原样透传） */
    private static class PlaceholderMeta {
        private boolean required;
        private String defaultValue;
    }
}
