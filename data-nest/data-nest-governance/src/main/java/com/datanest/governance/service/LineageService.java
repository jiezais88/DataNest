package com.datanest.governance.service;

import com.datanest.task.core.entity.LineageRecord;
import com.datanest.task.core.mapper.LineageRecordMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 血缘查询服务
 * 血缘写入由 engineering-service 在 SQL/Python 节点执行成功后直接落表，
 * governance-service 仅提供查询/展示能力（ADR-S4-003）。
 */
@Service
public class LineageService {

    private final LineageRecordMapper lineageRecordMapper;

    public LineageService(LineageRecordMapper lineageRecordMapper) {
        this.lineageRecordMapper = lineageRecordMapper;
    }

    public List<LineageRecord> queryByTargetTable(String tableName) {
        return lineageRecordMapper.selectByTargetTable(tableName);
    }

    public List<LineageRecord> queryByDagId(Long dagId) {
        return lineageRecordMapper.selectByDagId(dagId);
    }
}
