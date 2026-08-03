package com.datanest.governance.service;

import com.datanest.governance.dto.LineageEdgeDTO;
import com.datanest.governance.dto.LineageGraphDTO;
import com.datanest.governance.dto.LineageNodeDTO;
import com.datanest.task.core.dto.ColumnRef;
import com.datanest.task.core.dto.LineageColumnLinkDTO;
import com.datanest.task.core.dto.LineageTableEdge;
import com.datanest.task.core.entity.LineageRecord;
import com.datanest.task.core.mapper.LineageRecordMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 血缘查询服务
 * 血缘写入由 worker 在 SQL/Python/同步任务执行成功后直接落表，
 * governance-service 仅提供查询/展示能力（ADR-S4-003）。
 * Sprint 5：新增表级血缘图谱、影响/溯源分析、字段级血缘链路。
 */
@Service
public class LineageService {

    /** 默认展示一层上下游（R5：节点过多时性能兜底） */
    private static final int DEFAULT_DEPTH = 1;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_COLUMN_DEPTH = 20;

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

    /**
     * 表级血缘图谱：以指定表为中心，向上下游各展开 depth 层。
     */
    public LineageGraphDTO buildTableGraph(String tableName, int depth) {
        return buildTableGraph(tableName, depth, "BOTH");
    }

    /**
     * 影响分析：从中心表向下游递归展开的子图。
     */
    public LineageGraphDTO impact(String tableName, int depth) {
        return buildTableGraph(tableName, depth, "DOWN");
    }

    /**
     * 溯源分析：从中心表向上游递归展开的子图。
     */
    public LineageGraphDTO source(String tableName, int depth) {
        return buildTableGraph(tableName, depth, "UP");
    }

    /**
     * 字段级血缘链路：以指定表.字段为中心，向上下游展开完整链路。
     */
    public List<LineageColumnLinkDTO> buildColumnLineage(String tableName, String columnName) {
        List<LineageColumnLinkDTO> links = new ArrayList<>();
        Set<String> visitedLinks = new HashSet<>();
        Set<String> visitedRefs = new HashSet<>();

        ColumnRef center = new ColumnRef(tableName, columnName);
        List<ColumnRef> currentLevel = List.of(center);
        visitedRefs.add(refKey(center));
        for (int d = 0; d < MAX_COLUMN_DEPTH && !currentLevel.isEmpty(); d++) {
            List<ColumnRef> nextLevel = new ArrayList<>();
            List<LineageColumnLinkDTO> upLinks = lineageRecordMapper.selectLinksByTargets(currentLevel);
            for (LineageColumnLinkDTO link : upLinks) {
                if (visitedLinks.add(linkKey(link))) {
                    links.add(link);
                }
                ColumnRef src = new ColumnRef(link.getSourceTable(), link.getSourceColumn());
                if (src.getTable() != null && src.getColumn() != null && visitedRefs.add(refKey(src))) {
                    nextLevel.add(src);
                }
            }
            List<LineageColumnLinkDTO> downLinks = lineageRecordMapper.selectLinksBySources(currentLevel);
            for (LineageColumnLinkDTO link : downLinks) {
                if (visitedLinks.add(linkKey(link))) {
                    links.add(link);
                }
                ColumnRef tgt = new ColumnRef(link.getTargetTable(), link.getTargetColumn());
                if (tgt.getTable() != null && tgt.getColumn() != null && visitedRefs.add(refKey(tgt))) {
                    nextLevel.add(tgt);
                }
            }
            currentLevel = nextLevel;
        }
        return links;
    }

    private LineageGraphDTO buildTableGraph(String tableName, int depth, String direction) {
        int maxDepth = clampDepth(depth);
        List<LineageNodeDTO> nodes = new ArrayList<>();
        List<LineageEdgeDTO> edges = new ArrayList<>();
        Set<String> visitedTables = new HashSet<>();
        Set<String> visitedEdges = new HashSet<>();

        nodes.add(toNode(tableName, null, true));
        visitedTables.add(tableName);

        List<String> currentLevel = List.of(tableName);
        for (int d = 0; d < maxDepth && !currentLevel.isEmpty(); d++) {
            List<String> nextLevel = new ArrayList<>();
            if (!"UP".equals(direction)) {
                List<LineageTableEdge> downEdges = lineageRecordMapper.selectDownstreamEdges(currentLevel);
                for (LineageTableEdge edge : downEdges) {
                    if (visitedEdges.add(edgeKey(edge.getSourceTable(), edge.getTargetTable()))) {
                        edges.add(new LineageEdgeDTO(edge.getSourceTable(), edge.getTargetTable(), edge.getLineageType()));
                    }
                    if (visitedTables.add(edge.getTargetTable())) {
                        nodes.add(toNode(edge.getTargetTable(), edge.getLineageType(), false));
                        nextLevel.add(edge.getTargetTable());
                    }
                }
            }
            if (!"DOWN".equals(direction)) {
                List<LineageTableEdge> upEdges = lineageRecordMapper.selectUpstreamEdges(currentLevel);
                for (LineageTableEdge edge : upEdges) {
                    if (visitedEdges.add(edgeKey(edge.getSourceTable(), edge.getTargetTable()))) {
                        edges.add(new LineageEdgeDTO(edge.getSourceTable(), edge.getTargetTable(), edge.getLineageType()));
                    }
                    if (visitedTables.add(edge.getSourceTable())) {
                        nodes.add(toNode(edge.getSourceTable(), edge.getLineageType(), false));
                        nextLevel.add(edge.getSourceTable());
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return new LineageGraphDTO(nodes, edges);
    }

    private int clampDepth(Integer depth) {
        if (depth == null || depth <= 0) {
            return DEFAULT_DEPTH;
        }
        return Math.min(depth, MAX_DEPTH);
    }

    private LineageNodeDTO toNode(String fullName, String type, boolean current) {
        String database = null;
        if (fullName != null) {
            int dot = fullName.indexOf('.');
            if (dot > 0) {
                database = fullName.substring(0, dot);
            }
        }
        return new LineageNodeDTO(fullName, fullName, database, type, current);
    }

    private String edgeKey(String source, String target) {
        return source + "\u0001" + target;
    }

    private String refKey(ColumnRef ref) {
        return ref.getTable() + "\u0001" + ref.getColumn();
    }

    private String linkKey(LineageColumnLinkDTO link) {
        return link.getSourceTable() + "\u0001" + link.getSourceColumn()
                + "\u0001" + link.getTargetTable() + "\u0001" + link.getTargetColumn();
    }
}
