package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.CreateTaskResultDTO;
import com.datanest.engineering.dto.TaskTemplateDTO;
import com.datanest.engineering.dto.TaskTemplateQueryRequest;
import com.datanest.engineering.dto.TaskTemplateSaveRequest;
import com.datanest.engineering.dto.TemplateCreateTaskRequest;
import com.datanest.engineering.service.TaskTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务模板库（Sprint 7 DD-09）：模板 CRUD + 一键创建任务（SYNC 本地 / COLLECT 远程 governance）。
 */
@Tag(name = "任务模板", description = "任务模板 CRUD 与从模板一键创建任务")
@RestController
@RequestMapping("/task-templates")
public class TaskTemplateController {

    private final TaskTemplateService taskTemplateService;

    public TaskTemplateController(TaskTemplateService taskTemplateService) {
        this.taskTemplateService = taskTemplateService;
    }

    @Operation(summary = "模板列表（全量）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping
    public Result<List<TaskTemplateDTO>> list(@Parameter(description = "类型过滤（SYNC/COLLECT）") @RequestParam(required = false) String type,
                                              @Parameter(description = "来源过滤（BUILTIN/CUSTOM）") @RequestParam(required = false) String category) {
        return Result.ok(taskTemplateService.list(type, category));
    }

    @Operation(summary = "模板分页查询", description = "对齐平台列表页 POST /page 约定")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<TaskTemplateDTO>> page(@RequestBody TaskTemplateQueryRequest request) {
        return Result.ok(taskTemplateService.listPage(request));
    }

    @Operation(summary = "新增任务模板")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<TaskTemplateDTO> create(@Valid @RequestBody TaskTemplateSaveRequest request) {
        return Result.ok(taskTemplateService.create(request));
    }

    @Operation(summary = "编辑任务模板")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<TaskTemplateDTO> update(@Parameter(description = "模板 ID") @PathVariable Long id, @Valid @RequestBody TaskTemplateSaveRequest request) {
        return Result.ok(taskTemplateService.update(id, request));
    }

    @Operation(summary = "删除任务模板")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "模板 ID") @PathVariable Long id) {
        taskTemplateService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "从模板一键创建任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/create-task")
    public Result<CreateTaskResultDTO> createTask(@Parameter(description = "模板 ID") @PathVariable Long id,
                                                  @Valid @RequestBody TemplateCreateTaskRequest request) {
        return Result.ok(taskTemplateService.createTask(id, request));
    }
}
