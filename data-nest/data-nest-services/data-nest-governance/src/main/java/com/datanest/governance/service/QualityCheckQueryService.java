package com.datanest.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.alert.api.AlertApi;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.entity.QualityCheckBatch;
import com.datanest.governance.entity.QualityCheckDetail;
import com.datanest.governance.entity.QualityRule;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.governance.mapper.QualityCheckBatchMapper;
import com.datanest.governance.mapper.QualityCheckDetailMapper;
import com.datanest.governance.mapper.QualityRuleMapper;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.dto.QualityCheckBatchDTO;
import com.datanest.task.core.dto.QualityCheckDetailDTO;
import com.datanest.task.core.dto.QualityCheckQueryRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 质量检查执行结果查询（微服务化 4.2）。
 * <p>
 * 自 task-core {@code QualityCheckService} 的查询路径搬入 governance 本地（QualityCheckController 使用），
 * 直接用 governance 本地 mapper 查库，不经 Feign。聚合语义与原 toBatchDTO/toDetailDTO 一致，
 * 明细/表/规则按批查询消除 N+1。alertHistories 反查保持经 alert-service 远程（RemoteCalls 降级为空列表）。
 */
@Service
public class QualityCheckQueryService {

    private final QualityCheckBatchMapper batchMapper;
    private final QualityCheckDetailMapper detailMapper;
    private final MetadataTableMapper tableMapper;
    private final QualityRuleMapper ruleMapper;
    private final AlertApi alertApi;

    public QualityCheckQueryService(QualityCheckBatchMapper batchMapper,
                                    QualityCheckDetailMapper detailMapper,
                                    MetadataTableMapper tableMapper,
                                    QualityRuleMapper ruleMapper,
                                    AlertApi alertApi) {
        this.batchMapper = batchMapper;
        this.detailMapper = detailMapper;
        this.tableMapper = tableMapper;
        this.ruleMapper = ruleMapper;
        this.alertApi = alertApi;
    }

    public QualityCheckBatch requireBatch(Long id) {
        QualityCheckBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_BATCH_NOT_FOUND, "质量检查批次不存在: " + id);
        }
        return batch;
    }

    public List<QualityCheckDetail> listDetailsByBatch(Long batchId) {
        return detailMapper.selectList(new QueryWrapper<QualityCheckDetail>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    /**
     * 分页查询批次列表（按 job / trigger_type / status 过滤）。
     */
    public PageResult<QualityCheckBatchDTO> listBatches(QualityCheckQueryRequest request) {
        QueryWrapper<QualityCheckBatch> wrapper = new QueryWrapper<>();
        if (request.getJobId() != null) {
            wrapper.eq("job_id", request.getJobId());
        }
        if (request.getTriggerType() != null && !request.getTriggerType().isBlank()) {
            wrapper.eq("trigger_type", request.getTriggerType());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            wrapper.eq("status", request.getStatus());
        }
        if (request.getStartTimeFrom() != null && !request.getStartTimeFrom().isBlank()
                && request.getStartTimeTo() != null && !request.getStartTimeTo().isBlank()) {
            // started_at 是 timestamp 类型，需转 LocalDateTime，否则 PG 报 timestamp >= varchar 类型错误
            wrapper.between("started_at",
                    LocalDateTime.parse(request.getStartTimeFrom()),
                    LocalDateTime.parse(request.getStartTimeTo()));
        }
        wrapper.orderByDesc("id");

        IPage<QualityCheckBatch> page = batchMapper.selectPage(
                new Page<>(request.getPage(), request.getPageSize()), wrapper);
        List<QualityCheckBatch> batches = page.getRecords();
        // 明细按批一次查出，按批次分组，消除逐批次查询的 N+1
        Map<Long, List<QualityCheckDetail>> detailsByBatch = listDetailsByBatches(
                batches.stream().map(QualityCheckBatch::getId).toList());
        List<QualityCheckBatchDTO> records = batches.stream()
                .map(batch -> toBatchDTO(batch, detailsByBatch.getOrDefault(batch.getId(), List.of())))
                .toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 批次详情（含明细）。
     */
    public QualityCheckBatchDTO getBatchDetail(Long batchId) {
        QualityCheckBatch batch = requireBatch(batchId);
        List<QualityCheckDetail> details = listDetailsByBatch(batchId);
        QualityCheckBatchDTO dto = toBatchDTO(batch, details);
        dto.setDetails(toDetailDTOs(details));
        // 告警对应：经 alert-service 按 quality_batch_id 远程反查本批次触发的告警记录
        // （含规则名/发送状态/时间），供详情展示；远程失败降级为空列表，不阻断详情查询
        dto.setAlertHistories(listAlertHistories(batchId));
        return dto;
    }

    /** 远程反查批次告警记录（Feign）；失败经 RemoteCalls 降级（warn + 计数）并返回空列表 */
    private List<com.datanest.alert.api.dto.AlertHistoryDTO> listAlertHistories(Long batchId) {
        return RemoteCalls.execute("alert.listByQualityBatch", () -> {
            var result = alertApi.listByQualityBatch(batchId);
            return result != null && result.data() != null ? result.data() : List.of();
        }, List.of());
    }

    /** 按批次 ID 集合批量查明细并分组（空集合直接返回空 Map，避免 in () 非法 SQL）。 */
    private Map<Long, List<QualityCheckDetail>> listDetailsByBatches(List<Long> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return Map.of();
        }
        return detailMapper.selectList(new QueryWrapper<QualityCheckDetail>()
                        .in("batch_id", batchIds).orderByAsc("id"))
                .stream()
                .collect(Collectors.groupingBy(QualityCheckDetail::getBatchId));
    }

    private QualityCheckBatchDTO toBatchDTO(QualityCheckBatch batch, List<QualityCheckDetail> details) {
        QualityCheckBatchDTO dto = new QualityCheckBatchDTO();
        dto.setId(batch.getId());
        dto.setJobId(batch.getJobId());
        dto.setJobName(batch.getJobName());
        dto.setTriggerType(batch.getTriggerType());
        dto.setStatus(batch.getStatus());
        dto.setStartedAt(batch.getStartedAt());
        dto.setEndedAt(batch.getEndedAt());
        dto.setDurationMs(batch.getDurationMs());
        dto.setErrorMessage(batch.getErrorMessage());
        dto.setCreatedAt(batch.getCreatedAt());
        // 汇总规则数/成功/失败（执行层语义：SQL 是否跑成功）
        dto.setRuleCount(details.size());
        dto.setSuccessCount((int) details.stream().filter(d -> d.getSuccess() != null && d.getSuccess() == 1).count());
        dto.setFailedCount(details.size() - (dto.getSuccessCount() == null ? 0 : dto.getSuccessCount()));
        // 汇总分级四档计数（判定层语义：结果是否达标，与成功/失败列并存）
        int pass = 0, warning = 0, severe = 0, unavailable = 0;
        for (QualityCheckDetail d : details) {
            String level = d.getResultLevel();
            if (AlertConstants.QUALITY_LEVEL_PASS.equals(level)) {
                pass++;
            } else if (AlertConstants.QUALITY_LEVEL_WARNING.equals(level)) {
                warning++;
            } else if (AlertConstants.QUALITY_LEVEL_SEVERE.equals(level)) {
                severe++;
            } else if (AlertConstants.QUALITY_LEVEL_UNAVAILABLE.equals(level)) {
                unavailable++;
            }
        }
        dto.setPassCount(pass);
        dto.setWarningCount(warning);
        dto.setSevereCount(severe);
        dto.setUnavailableCount(unavailable);
        return dto;
    }

    /** 明细批量转 DTO：表名/规则阈值按批查询回填，消除逐条 selectById 的 N+1。 */
    private List<QualityCheckDetailDTO> toDetailDTOs(List<QualityCheckDetail> details) {
        Map<Long, MetadataTable> tables = batchSelect(
                details.stream().map(QualityCheckDetail::getTableId).distinct().toList(),
                ids -> tableMapper.selectList(new QueryWrapper<MetadataTable>().in("id", ids))
                        .stream().collect(Collectors.toMap(MetadataTable::getId, Function.identity())));
        Map<Long, QualityRule> rules = batchSelect(
                details.stream().map(QualityCheckDetail::getRuleId).distinct().toList(),
                ids -> ruleMapper.selectList(new QueryWrapper<QualityRule>().in("id", ids))
                        .stream().collect(Collectors.toMap(QualityRule::getId, Function.identity())));
        return details.stream()
                .map(detail -> toDetailDTO(detail, tables, rules))
                .toList();
    }

    /** ID 集合批量查询的通用封装：空集合（或全 null）返回空 Map。 */
    private <T> Map<Long, T> batchSelect(List<Long> ids, Function<List<Long>, Map<Long, T>> loader) {
        List<Long> nonNull = ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return Map.of();
        }
        return loader.apply(nonNull);
    }

    private QualityCheckDetailDTO toDetailDTO(QualityCheckDetail detail,
                                              Map<Long, MetadataTable> tables,
                                              Map<Long, QualityRule> rules) {
        QualityCheckDetailDTO dto = new QualityCheckDetailDTO();
        dto.setId(detail.getId());
        dto.setBatchId(detail.getBatchId());
        dto.setRuleId(detail.getRuleId());
        dto.setRuleName(detail.getRuleName());
        dto.setRuleType(detail.getRuleType());
        dto.setTableId(detail.getTableId());
        dto.setResultMetric(detail.getResultMetric());
        dto.setResultValue(detail.getResultValue());
        dto.setResultLevel(detail.getResultLevel());
        dto.setSuccess(detail.getSuccess());
        dto.setErrorMessage(detail.getErrorMessage());
        dto.setExecutedSql(detail.getExecutedSql());
        dto.setCreatedAt(detail.getCreatedAt());
        if (detail.getTableId() != null) {
            MetadataTable table = tables.get(detail.getTableId());
            if (table != null) {
                dto.setTableName(table.getTableName());
            }
        }
        // 判定依据：经规则回填阈值，供前端展示"为什么判严重"
        if (detail.getRuleId() != null) {
            QualityRule rule = rules.get(detail.getRuleId());
            if (rule != null) {
                dto.setWarningThreshold(rule.getWarningThreshold());
                dto.setSevereThreshold(rule.getSevereThreshold());
            }
        }
        return dto;
    }
}
