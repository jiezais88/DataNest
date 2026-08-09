package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 标准合规检查结果分页查询请求。
 * Sprint6 扩展：在检查范围基础上增加分页参数与 ignored / violationType 筛选。
 * ignored 语义：null 或缺省=0（默认仅未忽略），0=仅未忽略，1=仅已忽略，2=全部（不过滤）。
 * violationType：可选，NAMING（命名规范）/ TYPE（字段类型），用于列表按违规类型筛选。
 */
@Schema(description = "标准合规检查结果分页查询请求")
@Data
@EqualsAndHashCode(callSuper = true)
public class ComplianceCheckPageRequest extends ComplianceCheckRequest {

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;

    @Schema(description = "是否忽略过滤（null/0=仅未忽略，1=仅已忽略，2=全部）")
    private Integer ignored;

    @Schema(description = "违规类型筛选（NAMING/TYPE，可空）")
    private String violationType;
}
