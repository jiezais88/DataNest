package com.datanest.engineering.controller;

import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagVersionPayload;
import com.datanest.engineering.service.DagVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DAG 版本管理接口
 */
@RestController
@RequestMapping("/dev/dags/{dagId}/versions")
public class DagVersionController {

    private final DagVersionService dagVersionService;

    public DagVersionController(DagVersionService dagVersionService) {
        this.dagVersionService = dagVersionService;
    }

    @GetMapping
    public Result<List<DagVersionPayload>> listVersions(@PathVariable Long dagId) {
        return Result.ok(dagVersionService.listVersions(dagId));
    }

    @PostMapping("/{versionNo}/rollback")
    public Result<DagVersionPayload> rollback(@PathVariable Long dagId,
                                              @PathVariable Integer versionNo) {
        return Result.ok(dagVersionService.rollback(dagId, versionNo));
    }

    @GetMapping("/compare")
    public Result<DagVersionPayload.DagVersionDiff> compare(@PathVariable Long dagId,
                                                            @RequestParam Integer left,
                                                            @RequestParam Integer right) {
        return Result.ok(dagVersionService.compare(dagId, left, right));
    }
}
