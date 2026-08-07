package com.datanest.engineering.service;

import com.datanest.engineering.dto.NodeExecutionLogDTO;
import com.datanest.engineering.entity.NodeExecutionLog;
import com.datanest.engineering.mapper.NodeExecutionLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DAG 节点执行日志查询服务
 */
@Service
public class NodeExecutionLogQueryService {

    private final NodeExecutionLogMapper nodeExecutionLogMapper;

    public NodeExecutionLogQueryService(NodeExecutionLogMapper nodeExecutionLogMapper) {
        this.nodeExecutionLogMapper = nodeExecutionLogMapper;
    }

    public List<NodeExecutionLogDTO> query(Long executionId, String nodeId) {
        List<NodeExecutionLog> list = nodeExecutionLogMapper.selectByExecutionAndNode(executionId, nodeId);
        return list.stream().map(this::toDTO).toList();
    }

    private NodeExecutionLogDTO toDTO(NodeExecutionLog log) {
        NodeExecutionLogDTO dto = new NodeExecutionLogDTO();
        dto.setId(log.getId());
        dto.setExecutionId(log.getExecutionId());
        dto.setNodeId(log.getNodeId());
        dto.setLevel(log.getLevel());
        dto.setMessage(log.getMessage());
        dto.setLineNum(log.getLineNum());
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }
}
