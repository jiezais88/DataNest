package com.datanest.governance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Python 质量规则脚本试跑请求（Sprint 7 DG-10，规则表单「测试脚本」按钮）。
 * 保存前用目标表真实数据在 governance 本地沙箱试跑，验证脚本可执行并查看返回 dict。
 */
@Data
public class QualityPythonScriptTestRequest {

    /** 目标表 metadata_table.id（连接注入与 read_table 目标） */
    @NotNull(message = "目标表不能为空")
    private Long tableId;

    /** Python 脚本（def check(df) 返回 dict） */
    @NotBlank(message = "Python 脚本不能为空")
    private String pythonScript;
}
