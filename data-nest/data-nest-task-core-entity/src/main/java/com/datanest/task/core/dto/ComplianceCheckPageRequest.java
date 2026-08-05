package com.datanest.task.core.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 标准合规检查结果分页查询请求。
 * Sprint6 扩展：在检查范围基础上增加分页参数与 ignored 筛选。
 * ignored 语义：null=全部，0=仅未忽略，1=仅已忽略。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ComplianceCheckPageRequest extends ComplianceCheckRequest {

    private Integer page = 1;

    private Integer pageSize = 10;

    private Integer ignored;
}
