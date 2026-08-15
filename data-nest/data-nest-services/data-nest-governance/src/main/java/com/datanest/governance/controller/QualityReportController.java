package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.QualityIssueItemDTO;
import com.datanest.governance.dto.QualityLevelTrendPointDTO;
import com.datanest.governance.dto.QualityReportOptionsDTO;
import com.datanest.governance.dto.QualityReportRequest;
import com.datanest.governance.dto.DatasourceScoreComparisonDTO;
import com.datanest.governance.dto.QualityReportSummaryDTO;
import com.datanest.governance.dto.QualityScoreDistributionDTO;
import com.datanest.governance.dto.QualityScoreTrendPointDTO;
import com.datanest.governance.service.QualityReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 质量报告 Controller（Sprint 8 F3，DG-07 完整版）。
 * <p>
 * 多维筛选（数据源/库/质量任务/时间范围）+ KPI + 四档分布趋势 + 表评分趋势 + 问题清单 + CSV 导出。
 * 查看四角色可见；导出与存量补算收窄到治理员/超管（PRD §8 T5）。
 */
@Tag(name = "质量报告", description = "多维质量报告：KPI / 四档趋势 / 评分趋势 / 问题清单 / CSV 导出 / 评分历史补算")
@RestController
@RequestMapping("/quality/report")
@SaCheckLogin
public class QualityReportController {

    private final QualityReportService qualityReportService;

    public QualityReportController(QualityReportService qualityReportService) {
        this.qualityReportService = qualityReportService;
    }

    @Operation(summary = "筛选联动选项", description = "数据源（含内置 Doris）/ 库（随数据源联动，带所属数据源供反向联动）/ 质量任务（随数据源联动）")
    @PostMapping("/options")
    public Result<QualityReportOptionsDTO> options(@Parameter(description = "数据源 ID（库列表联动用）") @RequestParam(required = false) Long datasourceId) {
        return Result.ok(qualityReportService.options(datasourceId));
    }

    @Operation(summary = "KPI 汇总", description = "检查批次数 / 规则明细数 / 平均评分（当前最新）/ 通过率（排除 UNAVAILABLE）/ 待处理问题（SEVERE+WARNING 计数）")
    @PostMapping("/summary")
    public Result<QualityReportSummaryDTO> summary(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.summary(request));
    }
    @Operation(summary = "表评分分布", description = "范围内 ONLINE 表当前评分按健康度计数 + 无评分表数（环图），与时间无关")
    @PostMapping("/score-distribution")
    public Result<QualityScoreDistributionDTO> scoreDistribution(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.scoreDistribution(request));
    }
    @Operation(summary = "数据源质量对比", description = "按数据源分组平均评分（当前最新评分 ⋈ ONLINE 表），均分降序")
    @PostMapping("/datasource-comparison")
    public Result<List<DatasourceScoreComparisonDTO>> datasourceComparison(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.datasourceComparison(request));
    }

    @Operation(summary = "四档分布趋势", description = "按天聚合 PASS/WARNING/SEVERE/UNAVAILABLE 明细数")
    @PostMapping("/level-trend")
    public Result<List<QualityLevelTrendPointDTO>> levelTrend(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.levelTrend(request));
    }

    @Operation(summary = "评分趋势", description = "tableId 为空 = 聚合模式（按天 AVG 评分，ONLINE 表口径）；非空 = 单表评分历史序列（quality_score_history）")
    @PostMapping("/score-trend")
    public Result<List<QualityScoreTrendPointDTO>> scoreTrend(@RequestBody QualityReportRequest request) {
        return Result.ok(qualityReportService.scoreTrend(request));
    }

    @Operation(summary = "问题清单分页", description = "范围内 WARNING/SEVERE 规则明细，倒序分页，阈值按规则回填（规则已删缺省）")
    @PostMapping("/issues")
    public Result<PageResult<QualityIssueItemDTO>> issues(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.issues(request));
    }

    @Operation(summary = "导出 Excel", description = "汇总 KPI + 当前筛选问题清单全量（xlsx 列宽自适应，超 5000 行截断）；直写响应流")
    @PostMapping("/export")
    @SaCheckPermission(PermissionCode.QUALITY_JOB_EXECUTE)
    public void export(@RequestBody(required = false) QualityReportRequest request,
                       HttpServletResponse response) throws IOException {
        // 产品化文件名：DataNest-质量报告-日期.xlsx；ASCII 兜底 + RFC5987 中文编码（导出统一规范：void + 响应流）
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = "DataNest-质量报告-" + date + ".xlsx";
        String asciiFilename = "DataNest-quality-report-" + date + ".xlsx";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + asciiFilename + "\"; filename*=UTF-8''"
                        + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        qualityReportService.export(request, response.getOutputStream());
    }

    @Operation(summary = "存量评分历史补算", description = "为有当前评分但无历史快照的表补写首次快照（幂等），返回补写条数")
    @PostMapping("/backfill-score-history")
    @SaCheckPermission(PermissionCode.QUALITY_JOB_EXECUTE)
    public Result<Integer> backfillScoreHistory() {
        return Result.ok(qualityReportService.backfillScoreHistory());
    }
}
