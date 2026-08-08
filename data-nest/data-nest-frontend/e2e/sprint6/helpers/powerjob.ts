import {request as pwRequest, type APIRequestContext} from '@playwright/test';
import {POWERJOB_BASE, POWERJOB_APP_PLATFORM, POWERJOB_APP_WORKER, POWERJOB_HANDLER_COMPLIANCE} from './data';

/**
 * PowerJob OpenAPI 客户端（TypeScript 复刻 SchedulerClient 的关键流程，替换原 XXL-JOB admin 客户端）。
 * 用于 E2E 验证平台定时任务（如 standardComplianceCheckHandler）可被 PowerJob 触发执行。
 *
 * 协议对齐 SchedulerClient（common 模块）：
 * - 无需鉴权（oms.auth.openapi.enable=false），全部 POST {base}/openApi/*
 * - 查任务：POST /openApi/fetchAllJob?appId=..（按 processorInfo / jobParams 过滤）
 * - 触发：POST /openApi/runJob?appId=..&jobId=..&instanceParams=..（instanceParams 不能 URL 编码）
 * - 实例：POST /openApi/fetchInstanceStatus?instanceId=.. / fetchInstanceInfo（终态 5=SUCCEED 4=FAILED）
 * - 响应包络 ResultDTO：success=true 成功，message 携带失败原因
 */

/** 实例终态：4=FAILED 5=SUCCEED 9=STOPPED 10=CANCELED */
export const INSTANCE_TERMINAL_STATUSES = [4, 5, 9, 10];
export const INSTANCE_STATUS_SUCCEED = 5;
export const INSTANCE_STATUS_FAILED = 4;

/** 软删除任务状态（deleteJob 为软删，fetchAllJob 仍列出，需排除） */
const JOB_STATUS_DELETED = 99;

export class PowerJobClient {
    private ctx: APIRequestContext;
    /** jobId → appId 缓存（runJob 需要 appId，fetchAllJob 时回填，与 SchedulerClient 反查思路一致） */
    private jobAppCache = new Map<number, number>();

    private constructor(ctx: APIRequestContext) {
        this.ctx = ctx;
    }

    static async create(): Promise<PowerJobClient> {
        const ctx = await pwRequest.newContext();
        return new PowerJobClient(ctx);
    }

    /** 拉取指定 App 下全部任务（回填 jobId→appId 缓存），appId 约定：平台=1、worker=2 */
    async fetchAllJob(appId = POWERJOB_APP_PLATFORM): Promise<any[]> {
        const json = this.parseJson(await this.post('/openApi/fetchAllJob', {appId: String(appId)}));
        const list = Array.isArray(json?.data) ? json.data : [];
        for (const job of list) {
            const id = Number(job?.id);
            if (!Number.isNaN(id)) {
                this.jobAppCache.set(id, appId);
            }
        }
        return list;
    }

    /** 按 processorInfo（原 XXL executorHandler 名）查找任务，返回 jobId（排除软删任务） */
    async findJobIdByProcessor(processorInfo = POWERJOB_HANDLER_COMPLIANCE, appId = POWERJOB_APP_PLATFORM): Promise<number> {
        const jobs = (await this.fetchAllJob(appId)).filter((j) => Number(j.status) !== JOB_STATUS_DELETED);
        const job = jobs.find((j) => j.processorInfo === processorInfo);
        if (!job) {
            throw new Error(`PowerJob 平台任务未找到: processorInfo=${processorInfo}, appId=${appId}`);
        }
        return Number(job.id);
    }

    /**
     * 按 App + processorInfo + jobParams 精确查找任务（质量定时任务注册的 jobParams = 业务 jobId）。
     * 质量任务注册在 App data-nest-worker（appId=2），processorInfo=qualityCheckExecuteHandler。
     */
    async findJobIdByProcessorAndParam(appId: number, processorInfo: string, jobParams: string): Promise<number> {
        const jobs = (await this.fetchAllJob(appId)).filter((j) => Number(j.status) !== JOB_STATUS_DELETED);
        const job = jobs.find((j) => j.processorInfo === processorInfo && String(j.jobParams) === jobParams);
        if (!job) {
            throw new Error(`PowerJob 任务未找到: appId=${appId}, processorInfo=${processorInfo}, jobParams=${jobParams}`);
        }
        return Number(job.id);
    }

    /** 手动触发一次任务执行（等价于定时触发，只是不走 cron 等待），返回 instanceId 字符串 */
    async runJob(jobId: number, instanceParams = ''): Promise<string> {
        const appId = await this.resolveAppId(jobId);
        const text = await this.post('/openApi/runJob', {
            appId: String(appId),
            jobId: String(jobId),
            instanceParams,
        });
        // instanceId 超出 JS Number 安全整数范围，JSON.parse 会丢精度（server 拒绝非法 instanceId），从原始文本提取
        const match = text.match(/"data"\s*:\s*(\d+)/);
        if (!match) {
            throw new Error(`PowerJob runJob 未返回 instanceId: ${text.slice(0, 200)}`);
        }
        return match[1];
    }

    /** 查询实例状态码（1=等待派发 2=等待接收 3=运行中 4=FAILED 5=SUCCEED 9=STOPPED 10=CANCELED） */
    async fetchInstanceStatus(instanceId: string | number): Promise<number> {
        const json = this.parseJson(await this.post('/openApi/fetchInstanceStatus', {instanceId: String(instanceId)}));
        return Number(json?.data);
    }

    /** 查询实例详情（status / result / 时间戳等；info.instanceId 为大数，JSON.parse 后精度不可靠，勿用于后续调用） */
    async fetchInstanceInfo(instanceId: string | number): Promise<any> {
        const json = this.parseJson(await this.post('/openApi/fetchInstanceInfo', {instanceId: String(instanceId)}));
        return json?.data;
    }

    /** 轮询实例直到终态（5=SUCCEED 4=FAILED 9/10=终止），返回实例详情 */
    async waitInstanceTerminal(instanceId: string | number, timeoutMs = 120_000, intervalMs = 2_000): Promise<any> {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
            const status = await this.fetchInstanceStatus(instanceId);
            if (INSTANCE_TERMINAL_STATUSES.includes(status)) {
                return this.fetchInstanceInfo(instanceId);
            }
            if (Date.now() > deadline) {
                throw new Error(`PowerJob 实例未在 ${timeoutMs}ms 内进入终态: instanceId=${instanceId}, status=${status}`);
            }
            await new Promise((r) => setTimeout(r, intervalMs));
        }
    }

    async dispose(): Promise<void> {
        await this.ctx.dispose();
    }

    /** 仅持有 jobId 时反查所属 appId（缓存未命中则依次扫两个预置 App） */
    private async resolveAppId(jobId: number): Promise<number> {
        const cached = this.jobAppCache.get(jobId);
        if (cached != null) {
            return cached;
        }
        for (const appId of [POWERJOB_APP_PLATFORM, POWERJOB_APP_WORKER]) {
            const jobs = await this.fetchAllJob(appId);
            if (jobs.some((j) => Number(j.id) === jobId)) {
                return appId;
            }
        }
        throw new Error(`PowerJob 任务不存在: jobId=${jobId}`);
    }

    /**
     * POST query param 风格请求（PowerJob OpenAPI 均为 query param），断言业务成功后返回原始响应文本。
     * 注意：instanceParams 不能 URL 编码——server 端按原样读取不解码（编码后 %2C 会原样传给 processor）；
     * instanceParams 内容为内部约定格式（逗号/冒号/数字），无 &、=、空格等需转义字符。其余参数均为数字，无需编码。
     * 返回原始文本而非解析后 JSON：instanceId 等大数字段 JSON.parse 会丢精度，调用方按需自行提取。
     */
    private async post(path: string, query: Record<string, string>): Promise<string> {
        const qs = Object.entries(query)
            .map(([k, v]) => (k === 'instanceParams' ? `${k}=${v}` : `${k}=${encodeURIComponent(v)}`))
            .join('&');
        const res = await this.ctx.fetch(`${POWERJOB_BASE}${path}?${qs}`, {method: 'POST'});
        const text = await res.text();
        this.parseJson(text, res.status());
        return text;
    }

    /** 解析并断言 PowerJob ResultDTO：success=true 成功，失败时 message 携带原因 */
    private parseJson(text: string, httpStatus = 0): any {
        let json: any = null;
        try {
            json = text ? JSON.parse(text) : null;
        } catch {
            throw new Error(`PowerJob 非 JSON 响应(${httpStatus}): ${text.slice(0, 200)}`);
        }
        if (!json?.success) {
            throw new Error(`PowerJob 业务失败: ${json?.message ?? '未知错误'}`);
        }
        return json;
    }
}
