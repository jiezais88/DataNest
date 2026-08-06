package com.datanest.engineering.controller;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.service.DagExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 子 DAG 异步触发内部端点（Sprint 5）。
 * 由 DS HTTP 任务回调（DagDsConverter 将异步 SUB_DAG 节点映射为 HTTP 任务，URL 指向本端点）。
 * 决策 ADR-S3-008：内部接口不鉴权，依赖 Docker 网络隔离 + gateway 白名单。
 * 触发后父节点状态由 DagExecutionSyncService 轮询 DS 任务实例同步（HTTP 成功 → SUCCESS），
 * 因此本端点无需回写 node_execution。
 */
@RestController
@RequestMapping("/dev/internal")
public class SubDagTriggerController {

    private static final Logger logger = LoggerFactory.getLogger(SubDagTriggerController.class);

    private final DagExecutionService dagExecutionService;

    public SubDagTriggerController(DagExecutionService dagExecutionService) {
        this.dagExecutionService = dagExecutionService;
    }

    /**
     * 触发子 DAG 独立执行（不等待结果）。
     * Body: { dagId, nodeId, subDagId, executionId }，executionId 为 DS 流程实例 ID。
     */
    @PostMapping("/subdag/trigger")
    public Result<Map<String, Object>> triggerSubDag(@RequestBody Map<String, Object> body) {
        Long subDagId = longOf(body.get("subDagId"));
        if (subDagId == null) {
            throw new BusinessException(ErrorCode.SUB_DAG_NOT_FOUND, "子 DAG 触发缺少 subDagId");
        }
        Long parentDagId = longOf(body.get("dagId"));
        logger.info("子 DAG 异步触发: subDagId={}, parentDagId={}, parentNodeId={}",
                subDagId, parentDagId, body.get("nodeId"));
        dagExecutionService.trigger(subDagId);
        return Result.ok(Map.of("triggered", true, "subDagId", subDagId));
    }

    private Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 本地异常处理：DS HTTP 任务只认 HTTP 状态码，触发失败必须返回非 2xx，
     * 否则 DS 会误判父节点成功。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handleBusinessException(BusinessException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(e.getErrorCode().getCode(), e.getMessage(), e.getData()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleException(Exception e) {
        logger.error("子 DAG 异步触发未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), e.getMessage()));
    }
}
