import {request as pwRequest, type APIRequestContext} from '@playwright/test';
import {XXL_ADMIN_BASE, XXL_ADMIN_USER, XXL_ADMIN_PASS, XXL_HANDLER_COMPLIANCE} from './data';

/**
 * XXL-JOB admin REST 客户端（TypeScript 复刻 SchedulerClient 的关键流程）。
 * 用于 E2E 验证平台定时任务（如 standardComplianceCheckHandler）可被 XXL-JOB 触发执行。
 *
 * 协议对齐 SchedulerClient：
 * - 登录：POST {base}/auth/doLogin（form: userName/password/ifRemember=on）→ 拿 Set-Cookie
 * - 查执行器分组：GET  {base}/jobgroup/pageList?appname=data-nest-job
 * - 查任务：GET     {base}/jobinfo/pageList?jobGroup=..&executorHandler=..
 * - 触发：POST     {base}/jobinfo/trigger（form: id/executorParam/addressList）
 */

const EXECUTOR_APPNAME = 'data-nest-job';

export class XxlClient {
    private ctx: APIRequestContext;
    private cookie = '';

    private constructor(ctx: APIRequestContext) {
        this.ctx = ctx;
    }

    static async create(): Promise<XxlClient> {
        const ctx = await pwRequest.newContext();
        const client = new XxlClient(ctx);
        await client.login();
        return client;
    }

    async login(): Promise<void> {
        const res = await this.ctx.fetch(`${XXL_ADMIN_BASE}/auth/doLogin`, {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            data: new URLSearchParams({
                userName: XXL_ADMIN_USER,
                password: XXL_ADMIN_PASS,
                ifRemember: 'on',
            }).toString(),
        });
        const setCookie = res.headers()['set-cookie'];
        if (!setCookie) {
            throw new Error(`XXL-JOB 登录失败（未返回 Cookie）: status=${res.status()}`);
        }
        // 仅保留 cookie 名=值部分（去掉 Path= 等属性），与 SchedulerClient 的单 cookie 用法对齐
        this.cookie = setCookie.split(';')[0];
    }

    /** 查执行器分组 id（appname=data-nest-job），不存在返回 null */
    async findJobGroup(appName = EXECUTOR_APPNAME): Promise<number | null> {
        const json = await this.get(`/jobgroup/pageList?offset=0&pagesize=10&appname=${encodeURIComponent(appName)}`);
        const list = this.extractPageList(json);
        if (list.length === 0) {
            return null;
        }
        const id = list[0].id;
        return Number(id);
    }

    /** 按 handler 名查找任务，返回 jobId（含 executorHandler 匹配校验） */
    async findJobIdByHandler(executorHandler = XXL_HANDLER_COMPLIANCE): Promise<number> {
        const jobGroup = await this.findJobGroup();
        if (jobGroup == null) {
            throw new Error(`XXL-JOB 执行器分组不存在: appname=data-nest-job`);
        }
        const json = await this.get(
            `/jobinfo/pageList?jobGroup=${jobGroup}&triggerStatus=-1&jobDesc=&executorHandler=${encodeURIComponent(executorHandler)}&author=&offset=0&pagesize=100`,
        );
        const list = this.extractPageList(json);
        const job = list.find((j) => j.executorHandler === executorHandler);
        if (!job) {
            throw new Error(`XXL-JOB 平台任务未找到: executorHandler=${executorHandler}`);
        }
        return Number(job.id);
    }

    /** 手动触发一次任务执行（等价于定时触发，只是不走 cron 等待） */
    async trigger(jobId: number, executorParam = ''): Promise<void> {
        await this.post('/jobinfo/trigger', {
            id: String(jobId),
            executorParam,
            addressList: '',
        });
    }

    async dispose(): Promise<void> {
        await this.ctx.dispose();
    }

    private extractPageList(json: any): any[] {
        const data = json?.data?.data;
        return Array.isArray(data) ? data : [];
    }

    private async get(path: string): Promise<any> {
        const res = await this.ctx.fetch(`${XXL_ADMIN_BASE}${path}`, {headers: {Cookie: this.cookie}});
        return this.parse(res);
    }

    private async post(path: string, form: Record<string, string>): Promise<any> {
        const res = await this.ctx.fetch(`${XXL_ADMIN_BASE}${path}`, {
            method: 'POST',
            headers: {
                Cookie: this.cookie,
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            data: new URLSearchParams(form).toString(),
        });
        return this.parse(res);
    }

    private async parse(res: any): Promise<any> {
        const text = await res.text();
        let json: any = null;
        try {
            json = text ? JSON.parse(text) : null;
        } catch {
            throw new Error(`XXL-JOB 非 JSON 响应(${res.status()}): ${text.slice(0, 200)}`);
        }
        const code = json?.code;
        if (code !== 200) {
            throw new Error(`XXL-JOB 业务失败(code=${code}): ${json?.msg}`);
        }
        return json;
    }
}
