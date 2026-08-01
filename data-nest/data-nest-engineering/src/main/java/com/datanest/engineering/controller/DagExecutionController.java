package com.datanest.engineering.controller;

import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagExecutionGlobalDto;
import com.datanest.engineering.dto.GlobalExecutionFilter;
import com.datanest.engineering.service.DagExecutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局 DAG 执行历史 API（Sprint 3 PRD §6.7.3）
 * <p>
 * 路由前缀：/dag-executions
 * 外部 URL（经 gateway StripPrefix=1）：/api/engineering/dag-executions
 * <p>
 * 与单 DAG 维度 /dev/dags/{id}/executions 互不干扰：
 * - 本端点支持全局模糊匹配 + 状态/触发方式/时间范围过滤
 * - 单 DAG 端点保留在 DagController 维持向后兼容（PRD §6.7.1 单 DAG 历史）
 *
 * Sprint 3 决策点：
 * - 单独建一个 Controller 而不是塞进 DagController：因为此端点不属于 /dev/dags 资源层级（不属于某个 DAG 的子资源），是顶层资源
 * - 入参用 @RequestParam 字符串解析时间：避免 @DateTimeFormat(ISO.DATE_TIME) 对 'Z' 后缀处理不一致的坑
 */
@RestController
@RequestMapping("/dag-executions")
public class DagExecutionController {

    private final DagExecutionService dagExecutionService;

    public DagExecutionController(DagExecutionService dagExecutionService) {
        this.dagExecutionService = dagExecutionService;
    }

    /**
     * GET /api/engineering/dag-executions
     * <p>
     * Query params（全部可选）：
     * - dagName：DAG 名称模糊匹配
     * - dagId：DAG id 精确过滤（任务列表「历史」跳入时使用，优先级高于 dagName 模糊）
     * - status：RUNNING / SUCCESS / FAILED / TERMINATED
     * - triggerType：MANUAL / CRON
     * - startTimeFrom：执行时间下界（ISO 8601，支持 "Z" 后缀 UTC 写法）
     * - startTimeTo：执行时间上界
     * - page：默认 1
     * - pageSize：默认 20
     */
    @GetMapping
    public Result<PageResult<DagExecutionGlobalDto>> listAll(
            @RequestParam(required = false) String dagName,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) Long dagId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String startTimeFrom,
            @RequestParam(required = false) String startTimeTo,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        GlobalExecutionFilter filter = new GlobalExecutionFilter();
        filter.setDagName(dagName);
        filter.setProjectName(projectName);
        filter.setDagId(dagId);
        filter.setStatus(status);
        filter.setTriggerType(triggerType);
        filter.setStartTimeFrom(startTimeFrom);
        filter.setStartTimeTo(startTimeTo);
        filter.setPage(page < 1 ? 1 : page);
        filter.setPageSize(pageSize < 1 ? 20 : (pageSize > 200 ? 200 : pageSize));
        return Result.ok(dagExecutionService.listAll(filter));
    }
}
