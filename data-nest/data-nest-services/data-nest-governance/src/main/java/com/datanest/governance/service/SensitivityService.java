package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.governance.dto.SensitivityAuditItemDTO;
import com.datanest.governance.dto.SensitivityTableItemDTO;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.entity.SensitivityChangeLog;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.governance.mapper.SensitivityChangeLogMapper;
import com.datanest.system.api.SystemUserApi;
import com.datanest.task.core.support.SystemUserResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据分级分类（Sprint 10 F5）：改级 / 批量改级 / API 开白 / 审计查询 / 分级列表分页。
 * <p>
 * 核心规则（技术文档 §4.4 + PRD §6.7/T5/T6）：
 * <ul>
 *   <li>三级：PUBLIC 公开 / INTERNAL 内部 / CONFIDENTIAL 机密，默认 PUBLIC。</li>
 *   <li>机密降级两步（Blocker 4）：CONFIDENTIAL → PUBLIC 直达禁止，必经 INTERNAL（CONFIDENTIAL → INTERNAL → PUBLIC）。</li>
 *   <li>开白（T6）：仅 INTERNAL 表可开白（api_exempted 0↔1），机密表恒为 0 不可开白；权限超管（Controller 层）。</li>
 *   <li>审计：改级（CHANGE_LEVEL）与开白（API_EXEMPT）均写 sensitivity_change_log。</li>
 * </ul>
 */
@Service
public class SensitivityService {

    private static final String PUBLIC = "PUBLIC";
    private static final String INTERNAL = "INTERNAL";
    private static final String CONFIDENTIAL = "CONFIDENTIAL";

    private static final String ACTION_CHANGE_LEVEL = "CHANGE_LEVEL";
    private static final String ACTION_API_EXEMPT = "API_EXEMPT";

    private static final Set<String> VALID_LEVELS = Set.of(PUBLIC, INTERNAL, CONFIDENTIAL);

    private final MetadataTableMapper metadataTableMapper;
    private final SensitivityChangeLogMapper auditLogMapper;
    private final SystemUserApi systemUserApi;
    private final EngineeringDatasourceApi datasourceApi;

    public SensitivityService(MetadataTableMapper metadataTableMapper,
                              SensitivityChangeLogMapper auditLogMapper,
                              SystemUserApi systemUserApi,
                              EngineeringDatasourceApi datasourceApi) {
        this.metadataTableMapper = metadataTableMapper;
        this.auditLogMapper = auditLogMapper;
        this.systemUserApi = systemUserApi;
        this.datasourceApi = datasourceApi;
    }

    /** 单表改级 */
    @Transactional(rollbackFor = Exception.class)
    public void updateSensitivity(Long tableId, String newLevel) {
        String level = normalizeLevel(newLevel);
        MetadataTable table = getTable(tableId);
        String oldLevel = table.getSensitivityLevel();
        validateDowngrade(oldLevel, level);
        if (Objects.equals(oldLevel, level)) {
            return; // 幂等：级别未变
        }
        applyLevel(table, level);
        writeAudit(table, oldLevel, level, ACTION_CHANGE_LEVEL, null);
    }

    /** 批量改级（全有或全无：任一表违反降级规则则整体拒绝，避免部分成功） */
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateSensitivity(List<Long> tableIds, String newLevel) {
        String level = normalizeLevel(newLevel);
        List<Long> distinctIds = tableIds.stream().filter(Objects::nonNull).distinct().toList();
        // 先校验全部表（表存在 + 降级规则），任一失败抛异常回滚
        List<MetadataTable> tables = distinctIds.stream().map(this::getTable).toList();
        for (MetadataTable table : tables) {
            validateDowngrade(table.getSensitivityLevel(), level);
        }
        // 校验通过后统一更新 + 审计
        for (MetadataTable table : tables) {
            String oldLevel = table.getSensitivityLevel();
            if (Objects.equals(oldLevel, level)) {
                continue;
            }
            applyLevel(table, level);
            writeAudit(table, oldLevel, level, ACTION_CHANGE_LEVEL, null);
        }
    }

    /** 内部表 API 开白/取消开白（仅 INTERNAL；机密表恒为 0 不可开白） */
    @Transactional(rollbackFor = Exception.class)
    public void updateApiExempt(Long tableId, Integer apiExempted) {
        if (apiExempted == null || (apiExempted != 0 && apiExempted != 1)) {
            throw new BusinessException(ErrorCode.API_EXEMPT_NOT_ALLOWED, "开白标记非法（仅 0/1）");
        }
        MetadataTable table = getTable(tableId);
        if (!INTERNAL.equals(table.getSensitivityLevel())) {
            throw new BusinessException(ErrorCode.API_EXEMPT_NOT_ALLOWED, "仅内部表可开白");
        }
        table.setApiExempted(apiExempted);
        table.setUpdatedBy(currentUserId());
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
        String remark = apiExempted == 1 ? "开白" : "取消开白";
        // 开白不改级别，old/new 均记当前级别，靠 action/remark 区分
        writeAudit(table, INTERNAL, INTERNAL, ACTION_API_EXEMPT, remark);
    }

    /** 分级变更审计分页（回填操作人用户名） */
    public PageResult<SensitivityAuditItemDTO> pageAudit(long page, long pageSize) {
        IPage<SensitivityChangeLog> p = auditLogMapper.selectPage(new Page<>(page, pageSize),
                new QueryWrapper<SensitivityChangeLog>().orderByDesc("id"));
        List<SensitivityAuditItemDTO> items = p.getRecords().stream().map(this::toAuditDTO).toList();
        fillOperatorNames(items);
        return PageResult.of(items, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 分级管理页表列表分页（敏感度筛选 + 数据源筛选 + 库/模式/表关键词；仅 ONLINE 表） */
    public PageResult<SensitivityTableItemDTO> pageTables(long page, long pageSize,
                                                          String sensitivityLevel, String keyword, Long datasourceId) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("source_status", "ONLINE");
        if (datasourceId != null) {
            wrapper.eq("datasource_id", datasourceId);
        }
        if (sensitivityLevel != null && !sensitivityLevel.isBlank()) {
            String level = sensitivityLevel.trim().toUpperCase();
            if (!VALID_LEVELS.contains(level)) {
                throw new BusinessException(ErrorCode.SENSITIVITY_LEVEL_INVALID);
            }
            wrapper.eq("sensitivity_level", level);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("table_name", kw)
                    .or().like("database_name", kw)
                    .or().like("schema_name", kw));
        }
        IPage<MetadataTable> p = metadataTableMapper.selectPage(new Page<>(page, pageSize),
                wrapper.orderByDesc("updated_at"));
        List<SensitivityTableItemDTO> items = p.getRecords().stream().map(this::toTableItemDTO).toList();
        fillDatasourceNames(items);
        fillItemUserNames(items);
        return PageResult.of(items, p.getTotal(), p.getCurrent(), p.getSize());
    }

    // ---------- 内部方法 ----------

    private String normalizeLevel(String newLevel) {
        if (newLevel == null || newLevel.isBlank()) {
            throw new BusinessException(ErrorCode.SENSITIVITY_LEVEL_INVALID);
        }
        String level = newLevel.trim().toUpperCase();
        if (!VALID_LEVELS.contains(level)) {
            throw new BusinessException(ErrorCode.SENSITIVITY_LEVEL_INVALID);
        }
        return level;
    }

    /** 机密降级两步：CONFIDENTIAL → PUBLIC 直达禁止（必经 INTERNAL） */
    private void validateDowngrade(String oldLevel, String newLevel) {
        if (CONFIDENTIAL.equals(oldLevel) && PUBLIC.equals(newLevel)) {
            throw new BusinessException(ErrorCode.CONFIDENTIAL_DOWNGRADE_FORBIDDEN);
        }
    }

    private MetadataTable getTable(Long tableId) {
        MetadataTable table = metadataTableMapper.selectTableDetailById(tableId);
        if (table == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        return table;
    }

    private void applyLevel(MetadataTable table, String level) {
        table.setSensitivityLevel(level);
        table.setUpdatedBy(currentUserId());
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
    }

    private void writeAudit(MetadataTable table, String oldLevel, String newLevel, String action, String remark) {
        SensitivityChangeLog log = new SensitivityChangeLog();
        log.setTableId(table.getId());
        log.setTableName(table.getTableName());
        log.setOldLevel(oldLevel);
        log.setNewLevel(newLevel);
        log.setAction(action);
        log.setRemark(remark);
        log.setOperatorId(currentUserId());
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    private SensitivityAuditItemDTO toAuditDTO(SensitivityChangeLog log) {
        SensitivityAuditItemDTO dto = new SensitivityAuditItemDTO();
        dto.setId(log.getId());
        dto.setTableId(log.getTableId());
        dto.setTableName(log.getTableName());
        dto.setOldLevel(log.getOldLevel());
        dto.setNewLevel(log.getNewLevel());
        dto.setAction(log.getAction());
        dto.setRemark(log.getRemark());
        dto.setOperatorId(log.getOperatorId());
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }

    private SensitivityTableItemDTO toTableItemDTO(MetadataTable t) {
        SensitivityTableItemDTO dto = new SensitivityTableItemDTO();
        dto.setTableId(t.getId());
        dto.setTableName(t.getTableName());
        dto.setDatabaseName(t.getDatabaseName());
        dto.setSchemaName(t.getSchemaName());
        dto.setDatasourceId(t.getDatasourceId());
        dto.setSensitivityLevel(t.getSensitivityLevel());
        dto.setApiExempted(t.getApiExempted());
        dto.setSourceStatus(t.getSourceStatus());
        dto.setOwnerUserId(t.getOwnerUserId());
        dto.setUpdatedBy(t.getUpdatedBy());
        dto.setUpdatedAt(t.getUpdatedAt());
        return dto;
    }

    private void fillOperatorNames(List<SensitivityAuditItemDTO> items) {
        List<Long> ids = items.stream().map(SensitivityAuditItemDTO::getOperatorId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nameMap = SystemUserResolver.usernames(systemUserApi, ids);
        for (SensitivityAuditItemDTO item : items) {
            item.setOperatorName(item.getOperatorId() == null ? null : nameMap.get(item.getOperatorId()));
        }
    }

    private void fillItemUserNames(List<SensitivityTableItemDTO> items) {
        List<Long> ids = items.stream()
                .flatMap(i -> java.util.stream.Stream.of(i.getUpdatedBy(), i.getOwnerUserId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nameMap = SystemUserResolver.usernames(systemUserApi, ids);
        for (SensitivityTableItemDTO item : items) {
            item.setUpdatedByName(item.getUpdatedBy() == null ? null : nameMap.get(item.getUpdatedBy()));
            item.setOwnerName(item.getOwnerUserId() == null ? null : nameMap.get(item.getOwnerUserId()));
        }
    }

    private void fillDatasourceNames(List<SensitivityTableItemDTO> items) {
        List<Long> dsIds = items.stream().map(SensitivityTableItemDTO::getDatasourceId)
                .filter(Objects::nonNull).distinct().toList();
        if (dsIds.isEmpty()) {
            return;
        }
        Map<Long, DataSourceInfo> dsMap = RemoteCalls.execute("engineering.datasource.batchGet", () -> {
            IdsRequest request = new IdsRequest();
            request.setIds(dsIds);
            Result<Map<Long, DataSourceInfo>> result = datasourceApi.batchGet(request);
            return result == null || result.data() == null ? Map.<Long, DataSourceInfo>of() : result.data();
        }, Map.of());
        for (SensitivityTableItemDTO item : items) {
            DataSourceInfo ds = dsMap.get(item.getDatasourceId());
            if (ds != null) {
                item.setDatasourceName(ds.getName());
            }
        }
    }
}
