package com.datanest.engineering.controller;

import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagVersionPayload;
import com.datanest.engineering.service.DagVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "DAG 版本", description = "DAG 版本列表/对比/回滚")
@RestController
@RequestMapping("/dev/dags/{dagId}/versions")
public class DagVersionController {

    private final DagVersionService dagVersionService;

    public DagVersionController(DagVersionService dagVersionService) {
        this.dagVersionService = dagVersionService;
    }

    @Operation(summary = "DAG 版本列表")
    @GetMapping
    public Result<List<DagVersionPayload>> listVersions(@Parameter(description = "DAG ID") @PathVariable Long dagId) {
        return Result.ok(dagVersionService.listVersions(dagId));
    }

    @Operation(summary = "回滚到指定版本")
    @PostMapping("/{versionNo}/rollback")
    public Result<DagVersionPayload> rollback(@Parameter(description = "DAG ID") @PathVariable Long dagId,
                                              @Parameter(description = "版本号") @PathVariable Integer versionNo) {
        return Result.ok(dagVersionService.rollback(dagId, versionNo));
    }

    @Operation(summary = "版本对比")
    @GetMapping("/compare")
    public Result<DagVersionPayload.DagVersionDiff> compare(@Parameter(description = "DAG ID") @PathVariable Long dagId,
                                                            @Parameter(description = "左版本号") @RequestParam Integer left,
                                                            @Parameter(description = "右版本号") @RequestParam Integer right) {
        return Result.ok(dagVersionService.compare(dagId, left, right));
    }
}
