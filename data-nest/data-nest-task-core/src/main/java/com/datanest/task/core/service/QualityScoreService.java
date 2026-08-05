package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.task.core.constant.QualityScoreConstants;
import com.datanest.task.core.dto.QualityScoreConfigDTO;
import com.datanest.task.core.dto.QualityScoreDTO;
import com.datanest.task.core.dto.QualityScoreQueryRequest;
import com.datanest.task.core.dto.QualityTableRuleResultDTO;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.QualityCheckDetail;
import com.datanest.task.core.entity.QualityRule;
import com.datanest.task.core.entity.QualityScore;
import com.datanest.task.core.entity.QualityScoreConfig;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.QualityCheckDetailMapper;
import com.datanest.task.core.mapper.QualityRuleMapper;
import com.datanest.task.core.mapper.QualityScoreConfigMapper;
import com.datanest.task.core.mapper.QualityScoreMapper;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 表级质量评分查询（Sprint 6 NG8）。
 * <p>
 * 提供单表评分、批量评分（血缘图谱回填用，按表名集合一次 IN 查询避免 N+1）、评分列表分页，
 * 以及元数据「质量」页签的按表规则最近结果查询、按表执行全部启用规则、全局扣分配置读写。
 * 数据在 {@link ScoreCalculator} 执行时写入，本服务只读查询（执行/配置为写操作）。
 */
@Service
public class QualityScoreService {

    private static final long DORIS_DATASOURCE_ID = -1L;

    private final QualityScoreMapper scoreMapper;
    private final DataSourceConnectionMapper dataSourceMapper;
    private final QualityRuleMapper ruleMapper;
    private final QualityCheckDetailMapper detailMapper;
    private final QualityScoreConfigMapper configMapper;
    private final QualityRuleService ruleService;
    private final QualityCheckTriggerService triggerService;

    public QualityScoreService(QualityScoreMapper scoreMapper,
                               DataSourceConnectionMapper dataSourceMapper,
                               QualityRuleMapper ruleMapper,
                               QualityCheckDetailMapper detailMapper,
                               QualityScoreConfigMapper configMapper,
                               QualityRuleService ruleService,
                               QualityCheckTriggerService triggerService) {
        this.scoreMapper = scoreMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.ruleMapper = ruleMapper;
        this.detailMapper = detailMapper;
        this.configMapper = configMapper;
        this.ruleService = ruleService;
        this.triggerService = triggerService;
    }

    /** 单表评分。 */
    public QualityScoreDTO getByTableId(Long tableId) {
        QualityScore s = scoreMapper.selectOne(new QueryWrapper<QualityScore>()
                .eq("table_id", tableId).last("limit 1"));
        return s == null ? null : toDTO(s);
    }

    /** 按表名集合批量查（血缘回填，未命中返回空列表，调用方按 null 处理）。 */
    public List<QualityScoreDTO> listByTableNames(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return List.of();
        }
        List<QualityScore> list = scoreMapper.selectList(new QueryWrapper<QualityScore>()
                .in("table_name", tableNames));
        return list.stream().map(this::toDTO).toList();
    }

    /** 评分列表分页（按关键字/数据源/健康度筛选）。 */
    public PageResult<QualityScoreDTO> listPage(QualityScoreQueryRequest request) {
        QueryWrapper<QualityScore> wrapper = new QueryWrapper<>();
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            wrapper.like("table_name", request.getKeyword());
        }
        if (request.getDatasourceId() != null) {
            wrapper.eq("datasource_id", request.getDatasourceId());
        }
        if (request.getHealthLevel() != null && !request.getHealthLevel().isBlank()) {
            wrapper.eq("health_level", request.getHealthLevel());
        }
        wrapper.orderByDesc("score");

        IPage<QualityScore> page = scoreMapper.selectPage(
                new Page<>(request.getPage(), request.getPageSize()), wrapper);
        List<QualityScoreDTO> records = page.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 按表查该表所有启用规则 + 最近一次检查结果（元数据「质量」页签规则结果列表）。
     * <p>
     * 每规则按 rule_id 取最近一次 detail（倒序取最新一条）回填 result_level / result_value / lastCheckedAt；
     * 无最近结果时 result_level 为 null（前端展示「未检查」）。按权重降序排序。
     */
    public List<QualityTableRuleResultDTO> listTableRuleResults(Long tableId) {
        List<QualityRule> rules = ruleMapper.selectList(new QueryWrapper<QualityRule>()
                .eq("table_id", tableId)
                .eq("enabled", 1)
                .orderByDesc("weight"));
        if (rules.isEmpty()) {
            return List.of();
        }
        List<Long> ruleIds = rules.stream().map(QualityRule::getId).toList();
        // 所属任务名回填（ruleId → 任务名列表）
        Map<Long, List<String>> jobNameMap = ruleService.listJobNamesByRuleIds(ruleIds);
        // 最近一次结果（每规则取最新一条 detail）
        Map<Long, QualityCheckDetail> latestByRule = latestDetailsByRule(ruleIds);

        return rules.stream()
                .map(r -> toRuleResultDTO(r, jobNameMap.get(r.getId()), latestByRule.get(r.getId())))
                .toList();
    }

    /**
     * 按表执行全部启用规则：逐条触发 worker 上的质量执行 XXL-JOB（param=rule:&lt;ruleId&gt;）异步执行。
     * 单条触发失败不中断其他规则；存在失败时抛聚合异常（避免静默遗漏）。
     */
    public void executeTableRules(Long tableId) {
        List<QualityRule> rules = ruleMapper.selectList(new QueryWrapper<QualityRule>()
                .eq("table_id", tableId)
                .eq("enabled", 1));
        if (rules.isEmpty()) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "该表没有启用中的质量规则，无法执行");
        }
        List<String> failed = new ArrayList<>();
        for (QualityRule rule : rules) {
            try {
                triggerService.triggerRule(rule.getId(), "MANUAL");
            } catch (Exception e) {
                failed.add(rule.getName() + "(" + e.getMessage() + ")");
            }
        }
        if (!failed.isEmpty()) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED,
                    "部分规则触发失败：" + String.join("；", failed));
        }
    }

    /** 读全局扣分配置（无配置行时用默认值返回，不落库）。 */
    public QualityScoreConfigDTO getConfig() {
        QualityScoreConfig config = loadConfig();
        QualityScoreConfigDTO dto = new QualityScoreConfigDTO();
        dto.setWarningDeduct(config.getWarningDeduct());
        dto.setSevereDeduct(config.getSevereDeduct());
        dto.setBadThreshold(config.getBadThreshold());
        return dto;
    }

    /** 更新全局扣分配置（单行 upsert，记录修改人/时间）。 */
    public void updateConfig(QualityScoreConfigDTO dto) {
        if (dto == null || dto.getWarningDeduct() == null || dto.getSevereDeduct() == null
                || dto.getBadThreshold() == null) {
            throw new BusinessException(ErrorCode.QUALITY_SCORE_CONFIG_INVALID,
                    "警告扣分/严重扣分/低分区阈值均不能为空");
        }
        if (dto.getWarningDeduct() <= 0 || dto.getSevereDeduct() <= 0 || dto.getBadThreshold() <= 0) {
            throw new BusinessException(ErrorCode.QUALITY_SCORE_CONFIG_INVALID,
                    "扣分值与低分区阈值必须为正整数");
        }
        if (dto.getWarningDeduct() > 100 || dto.getSevereDeduct() > 100 || dto.getBadThreshold() > 100) {
            throw new BusinessException(ErrorCode.QUALITY_SCORE_CONFIG_INVALID,
                    "扣分值与低分区阈值不能超过 100");
        }
        Long userId = currentUserId();
        LocalDateTime now = LocalDateTime.now();
        QualityScoreConfig existing = loadConfig();
        if (existing.getId() != null) {
            existing.setWarningDeduct(dto.getWarningDeduct());
            existing.setSevereDeduct(dto.getSevereDeduct());
            existing.setBadThreshold(dto.getBadThreshold());
            existing.setUpdatedBy(userId);
            existing.setUpdatedAt(now);
            configMapper.updateById(existing);
        } else {
            QualityScoreConfig cfg = new QualityScoreConfig();
            cfg.setWarningDeduct(dto.getWarningDeduct());
            cfg.setSevereDeduct(dto.getSevereDeduct());
            cfg.setBadThreshold(dto.getBadThreshold());
            cfg.setUpdatedBy(userId);
            cfg.setUpdatedAt(now);
            configMapper.insert(cfg);
        }
    }

    // ==================== private ====================

    /** 读单行配置；无则返回携带默认值的新对象（不落库，仅作读取/兜底）。 */
    private QualityScoreConfig loadConfig() {
        QualityScoreConfig config = configMapper.selectList(
                        new QueryWrapper<QualityScoreConfig>().orderByAsc("id").last("limit 1"))
                .stream().findFirst().orElse(null);
        if (config == null) {
            QualityScoreConfig def = new QualityScoreConfig();
            def.setWarningDeduct(10);
            def.setSevereDeduct(30);
            def.setBadThreshold(60);
            return def;
        }
        return config;
    }

    /** 批量取每条规则最近一次检查明细（按 rule_id 分组取 id 最大一条）。 */
    private Map<Long, QualityCheckDetail> latestDetailsByRule(List<Long> ruleIds) {
        List<QualityCheckDetail> details = detailMapper.selectList(new QueryWrapper<QualityCheckDetail>()
                .in("rule_id", ruleIds)
                .orderByAsc("rule_id").orderByAsc("id"));
        if (details.isEmpty()) {
            return Map.of();
        }
        Map<Long, QualityCheckDetail> map = new HashMap<>();
        for (QualityCheckDetail d : details) {
            // 按 rule_id 升序 + id 升序遍历，后出现的 id 更大即最新，直接覆盖
            map.put(d.getRuleId(), d);
        }
        return map;
    }

    private QualityTableRuleResultDTO toRuleResultDTO(QualityRule rule,
                                                      List<String> jobNames,
                                                      QualityCheckDetail latest) {
        QualityTableRuleResultDTO dto = new QualityTableRuleResultDTO();
        dto.setRuleId(rule.getId());
        dto.setRuleName(rule.getName());
        dto.setRuleType(rule.getType());
        dto.setJobName(jobNames == null || jobNames.isEmpty() ? null
                : jobNames.stream().filter(Objects::nonNull).filter(n -> !n.isBlank())
                .distinct().collect(Collectors.joining("、")));
        dto.setColumnName(rule.getColumnName());
        dto.setWeight(rule.getWeight());
        if (latest != null) {
            dto.setResultValue(latest.getResultValue());
            dto.setResultLevel(latest.getResultLevel());
            dto.setSuccess(latest.getSuccess());
            dto.setLastCheckedAt(latest.getCreatedAt());
        }
        return dto;
    }

    private QualityScoreDTO toDTO(QualityScore s) {
        QualityScoreDTO dto = new QualityScoreDTO();
        dto.setId(s.getId());
        dto.setTableId(s.getTableId());
        dto.setTableName(s.getTableName());
        dto.setDatasourceId(s.getDatasourceId());
        dto.setScore(s.getScore());
        dto.setHealthLevel(s.getHealthLevel());
        dto.setHealthLevelLabel(healthLabel(s.getHealthLevel()));
        dto.setPassRules(s.getPassRules());
        dto.setWarningRules(s.getWarningRules());
        dto.setSevereRules(s.getSevereRules());
        dto.setLastCheckedAt(s.getLastCheckedAt());
        if (s.getDatasourceId() != null && s.getDatasourceId() != DORIS_DATASOURCE_ID) {
            DataSourceConnection ds = dataSourceMapper.selectById(s.getDatasourceId());
            if (ds != null) {
                dto.setDatasourceName(ds.getName());
            }
        }
        return dto;
    }

    private String healthLabel(String level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case QualityScoreConstants.HEALTH_EXCELLENT -> "优秀";
            case QualityScoreConstants.HEALTH_GOOD -> "良好";
            case QualityScoreConstants.HEALTH_WARNING -> "一般";
            case QualityScoreConstants.HEALTH_BAD -> "差";
            default -> level;
        };
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
