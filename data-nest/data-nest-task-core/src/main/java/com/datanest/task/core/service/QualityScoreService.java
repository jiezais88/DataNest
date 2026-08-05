package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.model.PageResult;
import com.datanest.task.core.constant.QualityScoreConstants;
import com.datanest.task.core.dto.QualityScoreDTO;
import com.datanest.task.core.dto.QualityScoreQueryRequest;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.QualityScore;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.QualityScoreMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 表级质量评分查询（Sprint 6 NG8）。
 * <p>
 * 提供单表评分、批量评分（血缘图谱回填用，按表名集合一次 IN 查询避免 N+1）、评分列表分页。
 * 数据在 {@link ScoreCalculator} 执行时写入，本服务只读查询。
 */
@Service
public class QualityScoreService {

    private static final long DORIS_DATASOURCE_ID = -1L;

    private final QualityScoreMapper scoreMapper;
    private final DataSourceConnectionMapper dataSourceMapper;

    public QualityScoreService(QualityScoreMapper scoreMapper,
                               DataSourceConnectionMapper dataSourceMapper) {
        this.scoreMapper = scoreMapper;
        this.dataSourceMapper = dataSourceMapper;
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
}
