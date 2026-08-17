package com.datanest.governance.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.constant.DorisConstants;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.common.constant.SourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.governance.api.dto.LineageRecordBatchRequest;
import com.datanest.governance.api.dto.LineageRecordItemDTO;
import com.datanest.governance.api.dto.MetadataRefreshIfExistsRequest;
import com.datanest.governance.api.dto.MetadataRegisterColumnDTO;
import com.datanest.governance.api.dto.MetadataRegisterRequest;
import com.datanest.governance.api.dto.MetadataRemoveRequest;
import com.datanest.governance.entity.LineageRecord;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.entity.QualityRule;
import com.datanest.governance.mapper.LineageRecordMapper;
import com.datanest.governance.mapper.MetadataColumnMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.governance.mapper.QualityRuleMapper;
import com.datanest.governance.service.AssetCollaborationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据写入 + 血缘域内部逻辑（实现 governance-api 的 MetadataWriteApi 契约）。
 * <p>
 * 微服务化 4.1：从 data-nest-task-core 的 MetadataRegistrationService / SqlLineageExtractor 搬运，
 * Doris 连接与 SQL 解析留在 task 侧，本服务只负责治理库（metadata_table/metadata_column/lineage_record）的读写。
 */
@Service
public class MetadataWriteService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataWriteService.class);
    private static final String SOURCE_TYPE = SourceType.BUILTIN_DORIS.getCode();
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final LineageRecordMapper lineageRecordMapper;
    private final AssetCollaborationService assetCollaborationService;
    private final QualityRuleMapper qualityRuleMapper;

    public MetadataWriteService(MetadataTableMapper metadataTableMapper,
                                MetadataColumnMapper metadataColumnMapper,
                                LineageRecordMapper lineageRecordMapper,
                                AssetCollaborationService assetCollaborationService,
                                QualityRuleMapper qualityRuleMapper) {
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.lineageRecordMapper = lineageRecordMapper;
        this.assetCollaborationService = assetCollaborationService;
        this.qualityRuleMapper = qualityRuleMapper;
    }

    /**
     * 元数据注册（一个事务）：findOrCreateTable + refreshColumns + column_count 更新。
     * 对齐原 MetadataRegistrationService.registerTable 语义，返回 tableId。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long register(MetadataRegisterRequest request) {
        MetadataTable table = findOrCreateTable(request.getDatabaseName(), request.getTableName(), request);
        List<MetadataColumn> columns = toColumns(request.getColumns(), table.getId());
        refreshColumns(table.getId(), columns);
        table.setColumnCount(columns.size());
        // 对齐 registerFromPython：created_by 仅在为空时回填，updated_by 按本次注册覆盖
        if (request.getOperatorId() != null) {
            if (table.getCreatedBy() == null) {
                table.setCreatedBy(request.getOperatorId());
            }
            table.setUpdatedBy(request.getOperatorId());
        }
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
        logger.info("注册 BUILTIN_DORIS 元数据表字段: tableId={}, table={}, count={}",
                table.getId(), request.getTableName(), columns.size());
        return table.getId();
    }

    /**
     * 表已存在才刷新（列结构 + column_count）；不存在则静默跳过。
     * 对齐原 MetadataRegistrationService.refreshIfExists 的语义（列结构由 task 侧提取后上报）。
     * 微服务化 4.2 契约补漏：请求携带 columns 时顺带 refreshColumns（4.1 仅回写 column_count，
     * ALTER 后的列结构变化会丢失）；operatorId 非空时回写 updated_by。
     */
    @Transactional(rollbackFor = Exception.class)
    public void refreshIfExists(MetadataRefreshIfExistsRequest request) {
        MetadataTable table = selectBuiltinTable(request.getDatabaseName(), request.getTableName());
        if (table == null) {
            logger.debug("元数据表 {}.{} 不存在，跳过刷新", request.getDatabaseName(), request.getTableName());
            return;
        }
        int columnCount = request.getColumnCount() == null ? 0 : request.getColumnCount();
        if (request.getColumns() != null) {
            List<MetadataColumn> columns = toColumns(request.getColumns(), table.getId());
            refreshColumns(table.getId(), columns);
            columnCount = columns.size();
        }
        if (request.getOperatorId() != null) {
            table.setUpdatedBy(request.getOperatorId());
        }
        table.setColumnCount(columnCount);
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
        logger.info("刷新 BUILTIN_DORIS 元数据: db={}, table={}, cols={}",
                request.getDatabaseName(), request.getTableName(), columnCount);
    }

    /**
     * 表存在时从元数据移除（DROP TABLE 场景：先删列再删表）；不存在则静默跳过。
     * 对齐原 MetadataRegistrationService.removeIfExists 语义。
     * Sprint 8 F1：表删除级联清理协作数据（标签绑定/收藏/关注/评论/热度，PRD §7 T4）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void remove(MetadataRemoveRequest request) {
        MetadataTable table = selectBuiltinTable(request.getDatabaseName(), request.getTableName());
        if (table == null) {
            logger.debug("元数据表 {}.{} 不存在，跳过删除", request.getDatabaseName(), request.getTableName());
            return;
        }
        // Sprint 11 收尾（2026-08-17）：删除前校验质量规则引用——表被质量规则作目标时禁止删除，
        // 否则规则悬空指向已删表（fail-closed，与数据源删除的引用校验语义一致）。
        // 注意 remove 是 worker 采集侧"发现表消失"的清理路径，静默删除会让质量规则悬空，
        // 因此需先处理相关质量规则再清理元数据。
        Long ruleCount = qualityRuleMapper.selectCount(
                new QueryWrapper<QualityRule>().eq("table_id", table.getId()));
        if (ruleCount != null && ruleCount > 0) {
            throw new BusinessException(ErrorCode.HAS_REFERENCES,
                    "元数据表已被 " + ruleCount + " 个质量规则引用，请先处理相关质量规则再删除");
        }
        metadataColumnMapper.delete(new QueryWrapper<MetadataColumn>().eq("table_id", table.getId()));
        metadataTableMapper.deleteById(table.getId());
        assetCollaborationService.deleteByTableIds(List.of(table.getId()));
        logger.info("删除 BUILTIN_DORIS 元数据: db={}, table={}", request.getDatabaseName(), request.getTableName());
    }

    /**
     * 血缘记录批量写入，返回插入条数。
     * 对齐原 SqlLineageExtractor.saveRecords 语义：单条走 insert，多条走 insertBatch。
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveLineageRecords(LineageRecordBatchRequest request) {
        List<LineageRecordItemDTO> items = request == null ? null : request.getRecords();
        if (items == null || items.isEmpty()) {
            return 0;
        }
        // 防御性过滤：无目标表的记录无血缘意义（如 DROP/USE 语句的解析占位），
        // 不过滤会导致 target_table 非空约束报错、整批写入失败并触发 Feign 熔断（Sprint 12 实测）
        items = items.stream()
                .filter(it -> it != null && it.getTargetTable() != null && !it.getTargetTable().isBlank())
                .toList();
        if (items.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<LineageRecord> records = new ArrayList<>(items.size());
        for (LineageRecordItemDTO item : items) {
            LineageRecord record = new LineageRecord();
            record.setSourceTable(item.getSourceTable());
            record.setSourceColumn(item.getSourceColumn());
            record.setTargetTable(item.getTargetTable());
            record.setTargetColumn(item.getTargetColumn());
            record.setDagId(item.getDagId());
            record.setDagName(item.getDagName());
            record.setNodeId(item.getNodeId());
            record.setNodeName(item.getNodeName());
            record.setExecutionId(item.getExecutionId());
            record.setLineageType(item.getLineageType());
            record.setCreatedAt(now);
            records.add(record);
        }
        if (records.size() == 1) {
            lineageRecordMapper.insert(records.get(0));
            return 1;
        }
        return lineageRecordMapper.insertBatch(records);
    }

    /**
     * 查找或新建内置 Doris 元数据表（datasource_id=-1）。
     * 对齐原 MetadataRegistrationService.findOrCreateTable：
     * 新建时 source_status=ONLINE、source_type=BUILTIN_DORIS、schema_name=null；
     * 已存在时 source_type 保持不变，任务来源字段按本次注册覆盖。
     */
    private MetadataTable findOrCreateTable(String targetDb, String targetTableName, MetadataRegisterRequest request) {
        MetadataTable existing = selectBuiltinTable(targetDb, targetTableName);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            MetadataTable table = new MetadataTable();
            table.setDatasourceId(DorisConstants.BUILTIN_DORIS_DATASOURCE_ID);
            table.setDatabaseName(targetDb);
            table.setSchemaName(null);
            table.setTableName(targetTableName);
            table.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
            table.setSourceType(SOURCE_TYPE);
            applySourceContext(table, request);
            table.setColumnCount(0);
            table.setCreatedAt(now);
            table.setUpdatedAt(now);
            metadataTableMapper.insert(table);
            logger.info("新增 BUILTIN_DORIS 元数据表: table={}", targetTableName);
            return table;
        }
        existing.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
        // source_type 保持不变；任务来源字段按本次注册覆盖
        applySourceContext(existing, request);
        existing.setUpdatedAt(now);
        metadataTableMapper.updateById(existing);
        logger.info("更新 BUILTIN_DORIS 元数据表: tableId={}, table={}", existing.getId(), targetTableName);
        return existing;
    }

    /**
     * 按内置 Doris（datasource_id=-1）+ 库名 + 空 schema + 表名定位元数据表。
     * 对齐原 MetadataRegistrationService 的查询条件（COALESCE(schema_name, '') = ''）。
     */
    private MetadataTable selectBuiltinTable(String targetDb, String targetTableName) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", DorisConstants.BUILTIN_DORIS_DATASOURCE_ID)
                .eq("database_name", targetDb)
                .apply("COALESCE(schema_name, '') = {0}", "")
                .eq("table_name", targetTableName);
        return metadataTableMapper.selectOne(wrapper);
    }

    /**
     * 任务来源字段覆盖（仅非空字段）。
     * 对齐原 MetadataRegistrationService.applySourceContext。
     */
    private void applySourceContext(MetadataTable table, MetadataRegisterRequest request) {
        if (request == null) return;
        if (request.getSourceTaskType() != null) table.setTaskSourceType(request.getSourceTaskType());
        if (request.getSourceDagId() != null) table.setSourceDagId(request.getSourceDagId());
        if (request.getSourceDagName() != null) table.setSourceDagName(request.getSourceDagName());
        if (request.getSourceNodeId() != null) table.setSourceNodeId(request.getSourceNodeId());
        if (request.getSourceNodeName() != null) table.setSourceNodeName(request.getSourceNodeName());
    }

    /**
     * 把请求中的字段列表转为 metadata_column 实体（source_type=BUILTIN_DORIS）。
     * 对齐原 MetadataRegistrationService.extractColumns 的字段组装。
     */
    private List<MetadataColumn> toColumns(List<MetadataRegisterColumnDTO> columnDTOs, Long tableId) {
        List<MetadataColumn> columns = new ArrayList<>();
        if (columnDTOs == null) {
            return columns;
        }
        for (MetadataRegisterColumnDTO dto : columnDTOs) {
            MetadataColumn column = new MetadataColumn();
            column.setTableId(tableId);
            column.setColumnName(dto.getColumnName());
            column.setDataType(dto.getColumnType());
            column.setColumnComment(dto.getComment());
            column.setNullable(dto.getNullable());
            column.setColumnDefault(dto.getColumnDefault());
            column.setOrdinalPosition(dto.getOrdinalPosition());
            column.setSourceType(SOURCE_TYPE);
            columns.add(column);
        }
        return columns;
    }

    /**
     * 刷新字段：已有字段按 column_name 匹配更新，且 column_comment/manual_comment 保留原值不被覆盖；
     * 新字段直接插入。
     * 对齐原 MetadataRegistrationService.refreshColumns。
     */
    private void refreshColumns(Long tableId, List<MetadataColumn> columns) {
        LocalDateTime now = LocalDateTime.now();

        Map<String, MetadataColumn> existingMap = new HashMap<>();
        List<MetadataColumn> existingColumns = metadataColumnMapper.selectList(
                new QueryWrapper<MetadataColumn>().eq("table_id", tableId));
        for (MetadataColumn existing : existingColumns) {
            existingMap.put(existing.getColumnName(), existing);
        }

        for (MetadataColumn column : columns) {
            MetadataColumn existing = existingMap.get(column.getColumnName());
            if (existing != null) {
                column.setId(existing.getId());
                column.setColumnComment(existing.getColumnComment());
                column.setManualComment(existing.getManualComment());
                column.setUpdatedAt(now);
                metadataColumnMapper.updateById(column);
            } else {
                column.setCreatedAt(now);
                column.setUpdatedAt(now);
                metadataColumnMapper.insert(column);
            }
        }
    }
}
