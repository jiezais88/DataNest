package com.datanest.task.core.dto;

import lombok.Data;

import java.util.List;

/**
 * Python 脚本执行结果
 */
@Data
public class PythonExecuteResult {

    private boolean success;
    private boolean timeout;
    private String stdout;
    private String stderr;
    private Integer exitCode;
    private Object output;
    private List<String> outputTables;
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
