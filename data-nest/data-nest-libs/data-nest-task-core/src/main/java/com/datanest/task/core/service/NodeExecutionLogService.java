package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.NodeExecutionLog;
import com.datanest.task.core.mapper.NodeExecutionLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DAG 节点执行日志服务。
 * Sprint 4 下沉到 task-core，供 engineering 与 worker 共用。
 */
@Service
public class NodeExecutionLogService {

    private static final Logger logger = LoggerFactory.getLogger(NodeExecutionLogService.class);

    private final NodeExecutionLogMapper nodeExecutionLogMapper;

    public NodeExecutionLogService(NodeExecutionLogMapper nodeExecutionLogMapper) {
        this.nodeExecutionLogMapper = nodeExecutionLogMapper;
    }

    @Transactional
    public void saveLogs(Long executionId, String nodeId, List<LogLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        AtomicInteger lineNum = new AtomicInteger(nextLineNum(executionId, nodeId));
        List<NodeExecutionLog> entities = new ArrayList<>(lines.size());
        for (LogLine line : lines) {
            NodeExecutionLog log = new NodeExecutionLog();
            log.setExecutionId(executionId);
            log.setNodeId(nodeId);
            log.setLevel(line.level());
            log.setMessage(line.message());
            log.setLineNum(lineNum.getAndIncrement());
            log.setCreatedAt(now);
            entities.add(log);
        }
        // 批量写入：Mapper 无自定义 batch，循环插入
        for (NodeExecutionLog log : entities) {
            try {
                nodeExecutionLogMapper.insert(log);
            } catch (Exception e) {
                logger.warn("写入节点日志失败: executionId={}, nodeId={}", executionId, nodeId, e);
            }
        }
    }

    @Transactional
    public void appendLog(Long executionId, String nodeId, String level, String message) {
        NodeExecutionLog log = new NodeExecutionLog();
        log.setExecutionId(executionId);
        log.setNodeId(nodeId);
        log.setLevel(level);
        log.setMessage(message);
        log.setLineNum(nextLineNum(executionId, nodeId));
        log.setCreatedAt(LocalDateTime.now());
        nodeExecutionLogMapper.insert(log);
    }

    private int nextLineNum(Long executionId, String nodeId) {
        if (executionId == null || nodeId == null) {
            return 1;
        }
        Long count = nodeExecutionLogMapper.selectCount(
                new QueryWrapper<NodeExecutionLog>()
                        .eq("execution_id", executionId)
                        .eq("node_id", nodeId));
        return (int) (count == null ? 0 : count) + 1;
    }

    /**
     * 日志行（级别 + 内容）
     */
    public record LogLine(String level, String message) {
    }
}
