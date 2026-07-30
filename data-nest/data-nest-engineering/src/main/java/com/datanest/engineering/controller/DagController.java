package com.datanest.engineering.controller;

import com.datanest.engineering.dto.DagExecutionDTO;
import com.datanest.engineering.dto.DagPayload;
import com.datanest.engineering.service.DagExecutionService;
import com.datanest.engineering.service.DagService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dev/dags")
public class DagController {

    private final DagService dagService;
    private final DagExecutionService dagExecutionService;

    public DagController(DagService dagService, DagExecutionService dagExecutionService) {
        this.dagService = dagService;
        this.dagExecutionService = dagExecutionService;
    }

    @GetMapping
    public List<DagPayload> list(@RequestParam(required = false) Long projectId) {
        return dagService.list(projectId);
    }

    @GetMapping("/{id}")
    public DagPayload get(@PathVariable Long id) {
        return dagService.getDetail(id);
    }

    @PostMapping
    public DagPayload create(@RequestBody DagPayload payload) {
        return dagService.create(payload);
    }

    @PutMapping("/{id}")
    public DagPayload update(@PathVariable Long id, @RequestBody DagPayload payload) {
        return dagService.update(id, payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        dagService.delete(id);
    }

    @PostMapping("/{id}/trigger")
    public DagExecutionDTO trigger(@PathVariable Long id) {
        return dagExecutionService.trigger(id);
    }

    @PostMapping("/{id}/executions/{executionId}/stop")
    public void stop(@PathVariable Long id, @PathVariable Long executionId) {
        // Sprint 3 P2-1：路由保留 id 让前端符合 REST 风格（listByDag 子资源），
        // service 校验 executionId 属于 id 后再 stop；当前简化：只校验 id 非空
        if (id == null) {
            throw new com.datanest.common.exception.BusinessException(com.datanest.common.exception.ErrorCode.DAG_NOT_FOUND);
        }
        dagExecutionService.stop(executionId);
    }

    @GetMapping("/{id}/executions")
    public List<DagExecutionDTO> executions(@PathVariable Long id) {
        return dagExecutionService.listByDag(id);
    }
}
