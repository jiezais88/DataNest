package com.datanest.worker.controller;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.worker.service.DagNodeExecuteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DAG 节点内部回调接口（DS worker → data-nest-worker）
 * Sprint 4 架构调整：节点执行从 engineering 迁移到 worker。
 * 路由：/dev/internal/{sql,sync,python}/callback
 */
@RestController
@RequestMapping("/dev/internal")
public class DagNodeCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeCallbackController.class);

    private final DagNodeExecuteService dagNodeExecuteService;

    public DagNodeCallbackController(DagNodeExecuteService dagNodeExecuteService) {
        this.dagNodeExecuteService = dagNodeExecuteService;
    }

    @PostMapping("/sql/callback")
    public Result<Map<String, Integer>> sqlCallback(@RequestBody Map<String, Object> body) {
        return dagNodeExecuteService.handleSqlNode(body);
    }

    @PostMapping("/sync/callback")
    public Result<Map<String, Integer>> syncCallback(@RequestBody Map<String, Object> body) {
        return dagNodeExecuteService.handleSyncNode(body);
    }

    @PostMapping("/python/callback")
    public Result<Map<String, Object>> pythonCallback(@RequestBody Map<String, Object> body) {
        return dagNodeExecuteService.handlePythonNode(body);
    }

    @PostMapping("/unknown/callback")
    public Result<Map<String, Integer>> unknownCallback(@RequestBody Map<String, Object> body) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未知节点类型");
    }

    /**
     * 本地异常处理：DS HTTP 任务只认 HTTP 状态码，callback 失败时必须返回非 2xx，
     * 否则 DS 会把本次任务标记为 SUCCESS，导致 DataNest 侧没有真正执行却显示成功、也看不到日志。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handleBusinessException(BusinessException e) {
        HttpStatus status = (e.getErrorCode() == ErrorCode.DAG_ALREADY_RUNNING)
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(Result.fail(e.getErrorCode().getCode(), e.getMessage(), e.getData()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleException(Exception e) {
        logger.error("DAG 节点回调未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), e.getMessage()));
    }
}
