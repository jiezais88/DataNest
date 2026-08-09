package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "Python 脚本执行结果")
@Data
public class PythonExecuteResult {

    @Schema(description = "是否执行成功")
    private boolean success;
    @Schema(description = "是否超时")
    private boolean timeout;
    @Schema(description = "标准输出")
    private String stdout;
    @Schema(description = "标准错误")
    private String stderr;
    @Schema(description = "退出码")
    private Integer exitCode;
    @Schema(description = "脚本输出（成功时为 check 返回的 dict，失败时为错误信息）")
    private Object output;
    @Schema(description = "输出表名列表")
    private List<String> outputTables;
    @Schema(description = "执行耗时（毫秒）")
    private Long durationMs;

    public static PythonExecuteResult success(String stdout, String stderr, Object output,
                                              List<String> outputTables, Long durationMs) {
        PythonExecuteResult r = new PythonExecuteResult();
        r.setSuccess(true);
        r.setTimeout(false);
        r.setStdout(stdout);
        r.setStderr(stderr);
        r.setOutput(output);
        r.setOutputTables(outputTables);
        r.setDurationMs(durationMs);
        return r;
    }

    public static PythonExecuteResult failure(String stdout, String stderr, String errorMessage,
                                              Long durationMs) {
        PythonExecuteResult r = new PythonExecuteResult();
        r.setSuccess(false);
        r.setTimeout(false);
        r.setStdout(stdout);
        r.setStderr(stderr);
        r.setOutput(errorMessage);
        r.setDurationMs(durationMs);
        return r;
    }

    public static PythonExecuteResult timeout() {
        PythonExecuteResult r = new PythonExecuteResult();
        r.setSuccess(false);
        r.setTimeout(true);
        r.setStdout("");
        r.setStderr("Python 执行超时");
        r.setOutput("timeout");
        return r;
    }
}
