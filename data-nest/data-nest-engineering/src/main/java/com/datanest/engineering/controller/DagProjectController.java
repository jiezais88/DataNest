package com.datanest.engineering.controller;

import com.datanest.engineering.dto.DagProjectCreateRequest;
import com.datanest.engineering.dto.DagProjectDTO;
import com.datanest.engineering.dto.DagProjectUpdateRequest;
import com.datanest.engineering.service.DagProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DAG 项目管理 API
 * 前缀：/api/dev/dag-projects（被 gateway StripPrefix=1 剥掉 /api）
 */
@RestController
@RequestMapping("/dev/dag-projects")
public class DagProjectController {

    private final DagProjectService dagProjectService;

    public DagProjectController(DagProjectService dagProjectService) {
        this.dagProjectService = dagProjectService;
    }

    @GetMapping
    public List<DagProjectDTO> list() {
        return dagProjectService.list();
    }

    @GetMapping("/{id}")
    public DagProjectDTO get(@PathVariable Long id) {
        return dagProjectService.getById(id);
    }

    @PostMapping
    public DagProjectDTO create(@Valid @RequestBody DagProjectCreateRequest request) {
        return dagProjectService.create(request);
    }

    @PutMapping("/{id}")
    public DagProjectDTO update(@PathVariable Long id, @Valid @RequestBody DagProjectUpdateRequest request) {
        return dagProjectService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        dagProjectService.delete(id);
    }
}
