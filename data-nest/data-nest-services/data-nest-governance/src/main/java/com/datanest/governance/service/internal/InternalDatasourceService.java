package com.datanest.governance.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.constant.CollectMode;
import com.datanest.common.constant.CollectTaskStatus;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.constant.ScheduleType;
import com.datanest.common.constant.TaskTriggerType;
import com.datanest.governance.api.dto.AutoCreateCollectTaskRequest;
import com.datanest.governance.api.dto.DatasourceReferencesDTO;
import com.datanest.governance.api.dto.ReferenceItemDTO;
import com.datanest.governance.service.SchedulerService;
import com.datanest.task.core.entity.CollectTask;
import com.datanest.task.core.entity.LineageRecord;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.QualityRule;
import com.datanest.task.core.entity.QualityScore;
import com.datanest.task.core.mapper.CollectTaskMapper;
import com.datanest.task.core.mapper.ComplianceCleanupMapper;
import com.datanest.task.core.mapper.LineageRecordMapper;
import com.datanest.task.core.mapper.MetadataColumnMapper;
import com.datanest.task.core.mapper.MetadataTableMapper;
import com.datanest.task.core.mapper.QualityRuleMapper;
import com.datanest.task.core.mapper.QualityScoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 治理域数据源内部逻辑（实现 governance-api 的 GovernanceDatasourceApi 契约）。
 * <p>
 * 微服务化 3.4：engineering 对治理表（metadata_table/column、compliance_check_result、
 * quality_score、quality_rule、collect_task、lineage_record）的跨域读写收进本服务，engineering 改走 Feign。
 */
@Service
public class InternalDatasourceService {

    private static final Logger logger = LoggerFactory.getLogger(InternalDatasourceService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CollectTaskMapper collectTaskMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final ComplianceCleanupMapper complianceCleanupMapper;
    private final QualityScoreMapper qualityScoreMapper;
    private final QualityRuleMapper qualityRuleMapper;
    private final LineageRecordMapper lineageRecordMapper;
    private final SchedulerService schedulerService;

    public InternalDatasourceService(CollectTaskMapper collectTaskMapper,
                                     MetadataTableMapper metadataTableMapper,
                                     MetadataColumnMapper metadataColumnMapper,
                                     ComplianceCleanupMapper complianceCleanupMapper,
                                     QualityScoreMapper qualityScoreMapper,
                                     QualityRuleMapper qualityRuleMapper,
                                     LineageRecordMapper lineageRecordMapper,
                                     SchedulerService schedulerService) {
        this.collectTaskMapper = collectTaskMapper;
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.complianceCleanupMapper = complianceCleanupMapper;
        this.qualityScoreMapper = qualityScoreMapper;
        this.qualityRuleMapper = qualityRuleMapper;
        this.lineageRecordMapper = lineageRecordMapper;
        this.schedulerService = schedulerService;
    }

    /**
     * 数据源在治理域的引用检查。collectTasks/qualityRules 为阻断删除的引用；
     * metadataTables 随 cascade-delete 级联清理，仅作信息返回。
     */
    public DatasourceReferencesDTO getReferences(Long datasourceId) {
        DatasourceReferencesDTO dto = new DatasourceReferencesDTO();

        dto.setCollectTasks(collectTaskMapper.selectActiveByDatasourceId(datasourceId).stream()
                .map(t -> toItem(t.getId(), t.getName()))
                .toList());

        List<MetadataTable> tables = metadataTableMapper.selectList(new QueryWrapper<MetadataTable>()
                .eq("datasource_id", datasourceId)
                .select("id", "table_name"));
        dto.setMetadataTables(tables.stream()
                .map(t -> toItem(t.getId(), t.getTableName()))
                .toList());

        List<Long> tableIds = tables.stream().map(MetadataTable::getId).toList();
        if (tableIds.isEmpty()) {
            dto.setQualityRules(List.of());
        } else {
            dto.setQualityRules(qualityRuleMapper.selectList(
                            new QueryWrapper<QualityRule>().in("table_id", tableIds))
                    .stream()
                    .map(r -> toItem(r.getId(), r.getName()))
                    .toList());
        }
        return dto;
    }

    /**
     * 级联删除数据源的治理域数据（本地事务，对齐原 engineering DataSourceService.delete 的顺序）：
     * metadata_column（按 tableIds）→ metadata_table → compliance_check_result → quality_score。
     */
    @Transactional
    public void cascadeDelete(Long datasourceId) {
        List<Long> tableIds = metadataTableMapper.selectIdsByDatasourceId(datasourceId);
        if (!tableIds.isEmpty()) {
            metadataColumnMapper.deleteByTableIds(tableIds);
        }
        metadataTableMapper.deleteByDatasourceId(datasourceId);

        int complianceRemoved = complianceCleanupMapper.deleteByDatasourceId(datasourceId);
        if (complianceRemoved > 0) {
            logger.info("级联删除合规检查结果: datasourceId={}, count={}", datasourceId, complianceRemoved);
        }

        int scoreRemoved = qualityScoreMapper.delete(
                new QueryWrapper<QualityScore>().eq("datasource_id", datasourceId));
        if (scoreRemoved > 0) {
            logger.info("级联删除质量评分: datasourceId={}, count={}", datasourceId, scoreRemoved);
        }
    }

    /**
     * 数据源保存后自动创建采集任务并立即触发（对齐原 engineering
     * DataSourceService.autoCreateAndRunCollectTask 的逻辑）：建 collect_task（FULL/MANUAL/不启调度）
     * → 注册 XXL-JOB 并回填 xxl_job_id → 立即触发一次。
     * 无外层事务（与原 afterCommit 语义一致：单行写入自动提交，远程调度失败由调用方按非阻断处理）。
     *
     * @return collectTaskId
     */
    public Long autoCreateCollectTask(AutoCreateCollectTaskRequest request) {
        LocalDateTime now = LocalDateTime.now();
        String prefix = "自动采集-";
        String suffix = "-" + now.format(TIMESTAMP_FORMATTER);
        String dsName = request.getDatasourceName() == null ? "" : request.getDatasourceName();
        int maxDsNameLen = 100 - prefix.length() - suffix.length();
        if (maxDsNameLen < 0) {
            maxDsNameLen = 0;
        }
        if (dsName.length() > maxDsNameLen) {
            dsName = dsName.substring(0, maxDsNameLen);
        }
        String taskName = prefix + dsName + suffix;
        List<String> scope = resolveCollectScope(request);

        CollectTask task = new CollectTask();
        task.setName(taskName);
        task.setDatasourceId(request.getDatasourceId());
        task.setDatasourceName(request.getDatasourceName());
        task.setScope(scope);
        task.setCollectMode(CollectMode.FULL.getCode());
        task.setTriggerType(TaskTriggerType.MANUAL.getCode());
        task.setStatus(CollectTaskStatus.NEVER_EXECUTED.getCode());
        task.setDescription("数据源保存时自动创建的元数据采集任务");
        task.setScheduleEnabled(0);
        task.setCreatedBy(request.getCreatedBy() == null ? 0L : request.getCreatedBy());
        task.setCreatedAt(now);
        collectTaskMapper.insert(task);

        Integer xxlJobId = schedulerService.registerJob(task.getId(), taskName, "",
                ScheduleType.fromTriggerType(TaskTriggerType.MANUAL.getCode()).getCode(), false);
        task.setXxlJobId(xxlJobId);
        collectTaskMapper.updateById(task);

        schedulerService.triggerJob(xxlJobId, task.getId() + "," + TaskTriggerType.MANUAL.getCode());
        logger.info("数据源保存后自动采集任务已触发: datasourceId={}, taskId={}, xxlJobId={}",
                request.getDatasourceId(), task.getId(), xxlJobId);
        return task.getId();
    }

    /** 按 dag_id 删除血缘记录（DAG 删除级联清理），返回删除条数。 */
    public int deleteLineageByDag(Long dagId) {
        int deleted = lineageRecordMapper.delete(new QueryWrapper<LineageRecord>().eq("dag_id", dagId));
        if (deleted > 0) {
            logger.info("级联删除 DAG 血缘: dagId={}, records={}", dagId, deleted);
        }
        return deleted;
    }

    /**
     * 采集范围推导（对齐原 engineering resolveCollectScope）：
     * 有 schema 层的类型（postgresql/oracle/sqlserver）取 schemaName，缺省 public（PG）或用户名（Oracle）；
     * 其余取 databaseName。
     */
    private List<String> resolveCollectScope(AutoCreateCollectTaskRequest request) {
        DataSourceType type = DataSourceType.fromCode(request.getType());
        if (type != null && type.hasSchemaLayer()) {
            String schema = StringUtils.hasText(request.getSchemaName()) ? request.getSchemaName()
                    : (type == DataSourceType.POSTGRESQL ? "public" : request.getUsername());
            return StringUtils.hasText(schema) ? Collections.singletonList(schema) : Collections.emptyList();
        }
        String scope = request.getDatabaseName();
        return StringUtils.hasText(scope) ? Collections.singletonList(scope) : Collections.emptyList();
    }

    private ReferenceItemDTO toItem(Long id, String name) {
        ReferenceItemDTO item = new ReferenceItemDTO();
        item.setId(id);
        item.setName(name);
        return item;
    }
}
