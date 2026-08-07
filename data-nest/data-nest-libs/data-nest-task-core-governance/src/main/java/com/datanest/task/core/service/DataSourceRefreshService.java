package com.datanest.task.core.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DataSourceStatus;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.DataSourceStatusUpdateRequest;
import com.datanest.task.core.dto.TestConnectionRequest;
import com.datanest.task.core.dto.TestConnectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源状态刷新核心，供 data-nest-job 与 engineering 同进程调用。
 * <p>
 * 微服务化 3.4：datasource_connection 归 engineering 所有，本类不再直连库，
 * 读（active 全量/单条）与状态回写均经 {@link EngineeringDatasourceApi} Feign
 * （engineering 容器内为 lb:// 自调用）。
 */
@Service
public class DataSourceRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceRefreshService.class);

    private final EngineeringDatasourceApi datasourceApi;
    private final ConnectionTester connectionTester;
    private final EncryptionConfig encryptionConfig;

    public DataSourceRefreshService(EngineeringDatasourceApi datasourceApi,
                                    ConnectionTester connectionTester,
                                    EncryptionConfig encryptionConfig) {
        this.datasourceApi = datasourceApi;
        this.connectionTester = connectionTester;
        this.encryptionConfig = encryptionConfig;
    }

    public void refreshAllStatuses() {
        // 定时刷新为读路径：engineering 不可用时降级为空列表（本轮不刷新），warn + 计数
        List<DataSourceInfo> list = RemoteCalls.execute("engineering.datasource.listActive", () -> {
            Result<List<DataSourceInfo>> result = datasourceApi.listActive();
            return result == null || result.data() == null ? List.<DataSourceInfo>of() : result.data();
        }, List.of());

        for (DataSourceInfo entity : list) {
            try {
                TestConnectionResult result = testAndUpdateStatus(entity.getId());
                logger.info("Refreshed data source status: id={}, name={}, success={}",
                        entity.getId(), entity.getName(), result.isSuccess());
            } catch (Exception e) {
                logger.error("Failed to refresh data source status: id={}, name={}", entity.getId(), entity.getName(), e);
                DataSourceStatusUpdateRequest errorUpdate = new DataSourceStatusUpdateRequest();
                errorUpdate.setStatus(DataSourceStatus.ERROR.getCode());
                errorUpdate.setErrorMessage("定时刷新异常: " + e.getMessage());
                errorUpdate.setLastTestTime(LocalDateTime.now());
                // 状态回写失败仅记 warn（下轮刷新会再试），不影响其余数据源
                RemoteCalls.execute("engineering.datasource.updateStatus",
                        () -> datasourceApi.updateStatus(entity.getId(), errorUpdate));
            }
        }
    }

    /**
     * 测试连接并回写状态。读连接 fail-fast（读不到抛 DATASOURCE_NOT_FOUND）；
     * 状态回写直接调用，异常传播给调用方（refreshAllStatuses 逐条兜底）。
     * 已无本地 DB 写，不再声明事务。
     */
    public TestConnectionResult testAndUpdateStatus(Long id) {
        Result<DataSourceInfo> readResult = datasourceApi.getById(id);
        DataSourceInfo entity = readResult == null ? null : readResult.data();
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
        updateStatus(id, result);
        return result;
    }

    private void updateStatus(Long id, TestConnectionResult result) {
        DataSourceStatusUpdateRequest update = new DataSourceStatusUpdateRequest();
        update.setStatus(result.isSuccess() ? DataSourceStatus.NORMAL.getCode() : DataSourceStatus.ERROR.getCode());
        update.setErrorMessage(result.isSuccess() ? null : result.getMessage());
        update.setLastTestTime(LocalDateTime.now());
        datasourceApi.updateStatus(id, update);
    }
}
