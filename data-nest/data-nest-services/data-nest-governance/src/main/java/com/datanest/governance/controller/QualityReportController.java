package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.QualityIssueItemDTO;
import com.datanest.governance.dto.QualityLevelTrendPointDTO;
import com.datanest.governance.dto.QualityReportOptionsDTO;
import com.datanest.governance.dto.QualityReportRequest;
import com.datanest.governance.dto.QualityReportSummaryDTO;
import com.datanest.governance.dto.QualityScoreTrendPointDTO;
import com.datanest.governance.service.QualityReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityReportController {

    private final QualityReportService qualityReportService;

    public QualityReportController(QualityReportService qualityReportService) {
        this.qualityReportService = qualityReportService;
    }

    @Operation(summary = "筛选联动选项", description = "数据源（含内置 Doris）/ 库（随数据源联动）/ 质量任务")
    @PostMapping("/options")
    public Result<QualityReportOptionsDTO> options(@Parameter(description = "数据源 ID（库列表联动用）") @RequestParam(required = false) Long datasourceId) {
        return Result.ok(qualityReportService.options(datasourceId));
    }

    @Operation(summary = "KPI 汇总", description = "检查批次数 / 规则明细数 / 平均评分（当前最新）/ 通过率（排除 UNAVAILABLE）")
    @PostMapping("/summary")
    public Result<QualityReportSummaryDTO> summary(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.summary(request));
    }

    @Operation(summary = "四档分布趋势", description = "按天聚合 PASS/WARNING/SEVERE/UNAVAILABLE 明细数")
    @PostMapping("/level-trend")
    public Result<List<QualityLevelTrendPointDTO>> levelTrend(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.levelTrend(request));
    }

    @Operation(summary = "表评分趋势", description = "按 tableId + 时间范围取评分历史序列（quality_score_history）")
    @PostMapping("/score-trend")
    public Result<List<QualityScoreTrendPointDTO>> scoreTrend(@RequestBody QualityReportRequest request) {
        return Result.ok(qualityReportService.scoreTrend(request));
    }

    @Operation(summary = "问题清单分页", description = "范围内 WARNING/SEVERE 规则明细，倒序分页，阈值按规则回填（规则已删缺省）")
    @PostMapping("/issues")
    public Result<PageResult<QualityIssueItemDTO>> issues(@RequestBody(required = false) QualityReportRequest request) {
        return Result.ok(qualityReportService.issues(request));
    }

    @Operation(summary = "导出 CSV", description = "汇总 KPI + 当前筛选问题清单全量（UTF-8 BOM，超 5000 行截断）")
    @PostMapping("/export")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public ResponseEntity<byte[]> export(@RequestBody(required = false) QualityReportRequest request) {
        String csv = qualityReportService.export(request);
        // 产品化文件名：DataNest-质量报告-日期.csv；ASCII 兜底 + RFC5987 中文编码（对齐合规/收藏导出）
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = "DataNest-质量报告-" + date + ".csv";
        String asciiFilename = "DataNest-quality-report-" + date + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiFilename + "\"; filename*=UTF-8''"
                                + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "存量评分历史补算", description = "为有当前评分但无历史快照的表补写首次快照（幂等），返回补写条数")
    @PostMapping("/backfill-score-history")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Integer> backfillScoreHistory() {
        return Result.ok(qualityReportService.backfillScoreHistory());
    }
}
