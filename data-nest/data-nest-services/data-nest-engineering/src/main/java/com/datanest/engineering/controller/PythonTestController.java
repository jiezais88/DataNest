package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.PythonTestRequest;
import com.datanest.task.core.dto.PythonExecuteResult;
import com.datanest.task.core.service.PythonExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Python 脚本独立测试接口（不依赖 DAG）。
 * <p>
 * 使用场景：新建 DAG 尚未保存时，Python 节点编辑器需要「运行测试」按钮可用。
 * 此时没有 dagId，无法解析 DAG 级参数，因此只执行脚本本身，不替换占位符。
 */
@RestController
@RequestMapping("/dev")
public class PythonTestController {

    private final PythonExecutor pythonExecutor;

    public PythonTestController(PythonExecutor pythonExecutor) {
        this.pythonExecutor = pythonExecutor;
    }

    /**
     * 独立执行 Python 脚本并返回结果，不注册元数据、不写 node_execution。
     * 请求体中的 params 如有值则作为上下文参数，但不进行 DAG 级参数解析/占位符替换。
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/python/test")
    public Result<PythonExecuteResult> testPythonScript(@RequestBody PythonTestRequest request) {
        if (request == null || !org.springframework.util.StringUtils.hasText(request.getPythonScript())) {
            return Result.fail(400, "pythonScript 不能为空");
        }
        Map<String, Object> params = request.getParams() != null ? request.getParams() : Collections.emptyMap();
        String script = request.getPythonScript();
        Integer timeoutSeconds = request.getTimeoutSeconds();
        PythonExecuteResult result = pythonExecutor.execute(
                script, new PythonExecutor.PythonContext(params, null), timeoutSeconds, null);
        return Result.ok(result);
    }
}
