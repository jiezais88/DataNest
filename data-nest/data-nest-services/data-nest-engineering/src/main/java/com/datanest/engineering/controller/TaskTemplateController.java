package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.CreateTaskResultDTO;
import com.datanest.engineering.dto.TaskTemplateDTO;
import com.datanest.engineering.dto.TaskTemplateSaveRequest;
import com.datanest.engineering.dto.TemplateCreateTaskRequest;
import com.datanest.engineering.service.TaskTemplateService;
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
@RestController
@RequestMapping("/task-templates")
public class TaskTemplateController {

    private final TaskTemplateService taskTemplateService;

    public TaskTemplateController(TaskTemplateService taskTemplateService) {
        this.taskTemplateService = taskTemplateService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping
    public Result<List<TaskTemplateDTO>> list(@RequestParam(required = false) String type,
                                              @RequestParam(required = false) String category) {
        return Result.ok(taskTemplateService.list(type, category));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<TaskTemplateDTO> create(@Valid @RequestBody TaskTemplateSaveRequest request) {
        return Result.ok(taskTemplateService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<TaskTemplateDTO> update(@PathVariable Long id, @Valid @RequestBody TaskTemplateSaveRequest request) {
        return Result.ok(taskTemplateService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taskTemplateService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/create-task")
    public Result<CreateTaskResultDTO> createTask(@PathVariable Long id,
                                                  @Valid @RequestBody TemplateCreateTaskRequest request) {
        return Result.ok(taskTemplateService.createTask(id, request));
    }
}
