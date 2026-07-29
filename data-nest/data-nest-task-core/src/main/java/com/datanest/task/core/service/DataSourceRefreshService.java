package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.dto.TestConnectionRequest;
import com.datanest.task.core.dto.TestConnectionResult;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源状态刷新核心，供 data-nest-job 与 engineering 同进程调用。
 */
@Service
public class DataSourceRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceRefreshService.class);
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_ERROR = "ERROR";

    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final ConnectionTester connectionTester;
    private final EncryptionConfig encryptionConfig;

    public DataSourceRefreshService(DataSourceConnectionMapper dataSourceConnectionMapper,
                                    ConnectionTester connectionTester,
                                    EncryptionConfig encryptionConfig) {
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.connectionTester = connectionTester;
        this.encryptionConfig = encryptionConfig;
    }

    public void refreshAllStatuses() {
        List<DataSourceConnection> list = dataSourceConnectionMapper.selectList(new QueryWrapper<DataSourceConnection>()
                .in("status", STATUS_NORMAL, STATUS_ERROR));

        for (DataSourceConnection entity : list) {
            try {
                TestConnectionResult result = testAndUpdateStatus(entity.getId());
                logger.info("Refreshed data source status: id={}, name={}, success={}",
                        entity.getId(), entity.getName(), result.isSuccess());
            } catch (Exception e) {
                logger.error("Failed to refresh data source status: id={}, name={}", entity.getId(), entity.getName(), e);
                entity.setStatus(STATUS_ERROR);
                entity.setErrorMessage("定时刷新异常: " + e.getMessage());
                entity.setLastTestTime(LocalDateTime.now());
                dataSourceConnectionMapper.updateById(entity);
            }
        }
    }

    @Transactional
    public TestConnectionResult testAndUpdateStatus(Long id) {
        DataSourceConnection entity = dataSourceConnectionMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }

        TestConnectionRequest request = new TestConnectionRequest();
        request.setType(entity.getType());
        request.setHost(entity.getHost());
        request.setPort(entity.getPort());
        request.setDatabaseName(entity.getDatabaseName());
        request.setSchemaName(entity.getSchemaName());
        request.setUsername(entity.getUsername());
        request.setPassword(encryptionConfig.decrypt(entity.getEncryptedPassword()));

        TestConnectionResult result = connectionTester.test(request);
        updateStatus(entity, result);
        return result;
    }

    private void updateStatus(DataSourceConnection entity, TestConnectionResult result) {
        entity.setStatus(result.isSuccess() ? STATUS_NORMAL : STATUS_ERROR);
        entity.setErrorMessage(result.isSuccess() ? null : result.getMessage());
        entity.setLastTestTime(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        dataSourceConnectionMapper.updateById(entity);
    }
}
