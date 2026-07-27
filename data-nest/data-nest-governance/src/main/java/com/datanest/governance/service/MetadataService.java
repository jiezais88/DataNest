package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.governance.dto.MetadataDatasourceDTO;
import com.datanest.governance.entity.DataSourceConnection;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.mapper.DataSourceConnectionMapper;
import com.datanest.governance.mapper.MetadataColumnMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MetadataService {

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;

    public MetadataService(MetadataTableMapper metadataTableMapper, MetadataColumnMapper metadataColumnMapper,
                           DataSourceConnectionMapper dataSourceConnectionMapper) {
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
    }

    @Transactional(readOnly = true)
    public List<MetadataDatasourceDTO> listDatasourceIds() {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT datasource_id").orderByAsc("datasource_id");
        List<Long> ids = metadataTableMapper.selectObjs(wrapper).stream()
                .map(o -> ((Number) o).longValue())
                .toList();

        if (ids.isEmpty()) {
            return List.of();
        }

        List<DataSourceConnection> connections = dataSourceConnectionMapper.selectList(
                Wrappers.<DataSourceConnection>query().in("id", ids));

        return ids.stream().map(id -> {
            MetadataDatasourceDTO dto = new MetadataDatasourceDTO();
            dto.setId(id);
            DataSourceConnection conn = connections.stream()
                    .filter(c -> id.equals(c.getId()))
                    .findFirst()
                    .orElse(null);
            if (conn != null) {
                dto.setName(conn.getName());
                dto.setType(conn.getType());
                dto.setExists(true);
            } else {
                dto.setName(null);
                dto.setType(null);
                dto.setExists(false);
            }
            return dto;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<String> listDatabases(Long datasourceId) {
        return metadataTableMapper.selectDatabasesByDatasourceId(datasourceId);
    }

    @Transactional(readOnly = true)
    public List<String> listSchemas(Long datasourceId, String databaseName) {
        return metadataTableMapper.selectSchemasByDatasourceIdAndDatabase(datasourceId, databaseName);
    }

    @Transactional(readOnly = true)
    public List<MetadataTable> listTables(Long datasourceId, String databaseName, String schemaName) {
        return metadataTableMapper.selectTablesByDatasourceDatabaseSchema(datasourceId, databaseName, schemaName);
    }

    @Transactional(readOnly = true)
    public MetadataTable getTable(Long tableId) {
        MetadataTable table = metadataTableMapper.selectTableDetailById(tableId);
        if (table == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        return table;
    }

    @Transactional(readOnly = true)
    public List<MetadataColumn> listColumns(Long tableId) {
        return metadataColumnMapper.selectByTableId(tableId);
    }

    @Transactional
    public void updateTableComment(Long tableId, String manualComment) {
        MetadataTable table = getTable(tableId);
        table.setManualComment(manualComment);
        table.setUpdatedBy(currentUserId());
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
    }

    @Transactional
    public void updateColumnComment(Long columnId, String manualComment) {
        MetadataColumn column = metadataColumnMapper.selectById(columnId);
        if (column == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        column.setManualComment(manualComment);
        column.setUpdatedBy(currentUserId());
        column.setUpdatedAt(LocalDateTime.now());
        metadataColumnMapper.updateById(column);
    }

    @Transactional
    public void updateColumnRemark(Long columnId, String remark) {
        MetadataColumn column = metadataColumnMapper.selectById(columnId);
        if (column == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        column.setRemark(remark);
        column.setUpdatedBy(currentUserId());
        column.setUpdatedAt(LocalDateTime.now());
        metadataColumnMapper.updateById(column);
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }
}
