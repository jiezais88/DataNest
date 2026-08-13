package com.datanest.dataservice.ws;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.dataservice.entity.ApiKeyPipeline;
import com.datanest.dataservice.mapper.ApiKeyPipelineMapper;
import com.datanest.governance.api.GovernanceMetadataApi;
import com.datanest.governance.api.dto.MetadataTableSensitivityDTO;
import com.datanest.realtime.api.CdcPipelineApi;
import com.datanest.realtime.api.dto.CdcPipelineSubscribeDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * WebSocket 订阅权校验（F4，T7/T5）：Key 绑定管道 + 管道 RUNNING + 表敏感度非机密。
 * <p>
 * fail-closed：治理服务不可达拒绝订阅（避免机密管道因治理故障被订阅，对齐 Blocker 3）。
 */
@Service
public class WsSubscriptionService {

    private static final String CONFIDENTIAL = "CONFIDENTIAL";
    private static final String RUNNING = "RUNNING";

    private final ApiKeyPipelineMapper pipelineBindingMapper;
    private final CdcPipelineApi cdcPipelineApi;
    private final GovernanceMetadataApi governanceMetadataApi;

    public WsSubscriptionService(ApiKeyPipelineMapper pipelineBindingMapper,
                                 CdcPipelineApi cdcPipelineApi,
                                 GovernanceMetadataApi governanceMetadataApi) {
        this.pipelineBindingMapper = pipelineBindingMapper;
        this.cdcPipelineApi = cdcPipelineApi;
        this.governanceMetadataApi = governanceMetadataApi;
    }

    /**
     * 校验订阅权，通过则返回管道订阅信息（含源表清单，后续 fan-out 可用）。
     *
     * @throws BusinessException Key 未绑定（9005）/ 管道不可用（9016）/ 机密表（9004）/ 治理不可达（9012）
     */
    public CdcPipelineSubscribeDTO checkSubscribe(Long keyId, Long pipelineId) {
        // 1. Key 绑定管道校验（T7：Key 绑定管道即获得订阅权）
        Long bound = pipelineBindingMapper.selectCount(new QueryWrapper<ApiKeyPipeline>()
                .eq("key_id", keyId).eq("pipeline_id", pipelineId));
        if (bound == null || bound == 0) {
            throw new BusinessException(ErrorCode.API_KEY_INVALID, "该 Key 未绑定此管道");
        }
        // 2. 管道存在且 RUNNING（realtime-api）
        Result<CdcPipelineSubscribeDTO> resp = cdcPipelineApi.getSubscribeInfo(pipelineId);
        if (resp == null || resp.code() != 200 || resp.data() == null) {
            throw new BusinessException(ErrorCode.API_PIPELINE_UNAVAILABLE, "管道不存在或已删除");
        }
        CdcPipelineSubscribeDTO info = resp.data();
        if (!RUNNING.equals(info.getStatus())) {
            throw new BusinessException(ErrorCode.API_PIPELINE_UNAVAILABLE,
                    "管道未运行，无法订阅（当前状态: " + info.getStatus() + "）");
        }
        // 3. 表敏感度（机密管道不可订阅，T5）
        checkSensitivity(info);
        return info;
    }

    private void checkSensitivity(CdcPipelineSubscribeDTO info) {
        if (info.getSourceTables() == null || info.getSourceTables().isEmpty()) {
            return;
        }
        Result<List<MetadataTableSensitivityDTO>> resp = governanceMetadataApi.getSensitivity(
                info.getSourceDatasourceId(), trimToNull(info.getSourceDatabase()), null,
                String.join(",", info.getSourceTables()));
        if (resp == null || resp.code() != 200) {
            throw new BusinessException(ErrorCode.SENSITIVITY_SERVICE_UNAVAILABLE,
                    "分级服务暂不可用，已阻止订阅，请稍后重试");
        }
        List<MetadataTableSensitivityDTO> list = resp.data();
        if (list == null) {
            return;
        }
        List<String> confidential = list.stream()
                .filter(d -> CONFIDENTIAL.equals(d.getSensitivityLevel()))
                .map(MetadataTableSensitivityDTO::getTableName)
                .toList();
        if (!confidential.isEmpty()) {
            throw new BusinessException(ErrorCode.TABLE_SENSITIVE,
                    "管道含机密数据表，禁止订阅: " + String.join(", ", confidential));
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
