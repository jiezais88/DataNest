package com.datanest.governance.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.ObjectNameRequest;
import com.datanest.governance.api.dto.ObjectOptionDTO;
import com.datanest.governance.api.dto.QualityAutoTriggerRequest;
import com.datanest.task.core.entity.CollectTask;
import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.mapper.CollectTaskMapper;
import com.datanest.task.core.mapper.QualityJobMapper;
import com.datanest.task.core.service.QualityAutoTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 治理域对象内部接口（实现 governance-api 的 Feign 契约）。
 * <p>
 * 仅供服务间内部调用（如告警服务解析对象名称/下拉选项、DAG 成功后自动触发质量任务），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal")
public class InternalObjectController {

    private static final Logger logger = LoggerFactory.getLogger(InternalObjectController.class);

    private static final String OBJECT_TYPE_COLLECT_TASK = "COLLECT_TASK";
    private static final String OBJECT_TYPE_QUALITY = "QUALITY";

    private final CollectTaskMapper collectTaskMapper;
    private final QualityJobMapper qualityJobMapper;
    private final QualityAutoTriggerService qualityAutoTriggerService;

    public InternalObjectController(CollectTaskMapper collectTaskMapper,
                                    QualityJobMapper qualityJobMapper,
                                    QualityAutoTriggerService qualityAutoTriggerService) {
        this.collectTaskMapper = collectTaskMapper;
        this.qualityJobMapper = qualityJobMapper;
        this.qualityAutoTriggerService = qualityAutoTriggerService;
    }

    /**
     * 按对象类型 + ID 列表批量查询对象名称；不支持的类型返回空 map。
     */
    @PostMapping("/objects/names")
    public Result<Map<Long, String>> names(@RequestBody ObjectNameRequest request) {
        String objectType = request.getObjectType() == null ? "" : request.getObjectType().toUpperCase();
        List<Long> ids = request.getIds() == null ? Collections.emptyList()
                : request.getIds().stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return Result.ok(names);
        }
        if (OBJECT_TYPE_COLLECT_TASK.equals(objectType)) {
            for (CollectTask task : collectTaskMapper.selectBatchIds(ids)) {
                names.put(task.getId(), task.getName());
            }
        } else if (OBJECT_TYPE_QUALITY.equals(objectType)) {
            for (QualityJob job : qualityJobMapper.selectBatchIds(ids)) {
                names.put(job.getId(), job.getName());
            }
        }
        return Result.ok(names);
    }

    /**
     * 按对象类型查询告警对象下拉选项：COLLECT_TASK / QUALITY 平铺返回；不支持的类型返回空列表。
     */
    @GetMapping("/alert-objects/options")
    public Result<List<ObjectOptionDTO>> options(@RequestParam String objectType) {
        String type = objectType == null ? "" : objectType.toUpperCase();
        if (OBJECT_TYPE_COLLECT_TASK.equals(type)) {
            return Result.ok(collectTaskMapper.selectList(null).stream()
                    .map(c -> {
                        ObjectOptionDTO option = new ObjectOptionDTO();
                        option.setId(c.getId());
                        option.setName(c.getName());
                        return option;
                    })
                    .toList());
        }
        if (OBJECT_TYPE_QUALITY.equals(type)) {
            return Result.ok(qualityJobMapper.selectList(null).stream()
                    .map(q -> {
                        ObjectOptionDTO option = new ObjectOptionDTO();
                        option.setId(q.getId());
                        option.setName(q.getName());
                        return option;
                    })
                    .toList());
        }
        return Result.ok(Collections.emptyList());
    }

    /**
     * 质量检查自动触发：对象成功完成后触发绑定它的启用质量任务（AUTO_TRIGGER）。
     * 异常包装为 Result 错误返回。
     */
    @PostMapping("/quality/auto-trigger")
    public Result<Void> qualityAutoTrigger(@RequestBody QualityAutoTriggerRequest request) {
        try {
            qualityAutoTriggerService.triggerOnSuccess(request.getObjectType(), request.getObjectId());
            return Result.ok(null);
        } catch (Exception e) {
            logger.error("质量任务自动触发失败: objectType={}, objectId={}",
                    request.getObjectType(), request.getObjectId(), e);
            return Result.fail("质量任务自动触发失败: " + e.getMessage());
        }
    }
}
