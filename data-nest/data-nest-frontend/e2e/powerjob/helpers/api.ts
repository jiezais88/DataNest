import {type APIRequestContext, request as pwRequest} from '@playwright/test';

/**
 * API 统一入口（gateway）
 * 所有业务请求携带 Authorization token（Sa-Token 直接放原始 token，无 Bearer 前缀）
 */
export const API_BASE = process.env.API_BASE ?? 'http://localhost:8080/api';

export interface Envelope<T = any> {
    code: number;
    message: string;
    data: T;
}

export class Api {
    readonly ctx: APIRequestContext;
    token?: string;

    private constructor(ctx: APIRequestContext) {
        this.ctx = ctx;
    }

    /** 新建一个未登录的 API 客户端 */
    static async create(): Promise<Api> {
        const ctx = await pwRequest.newContext();
        return new Api(ctx);
    }

    /** 以指定用户登录，返回携带 token 的客户端 */
    async login(username: string, password: string): Promise<Api> {
        const env = await this.raw('POST', '/system/auth/login', {username, password});
        if (env.code !== 200) {
            throw new Error(`登录失败: username=${username} env=${JSON.stringify(env)}`);
        }
        this.token = env.data.token;
        return this;
    }

    /** 原始请求，返回业务信封 {code, message, data}；不校验 code */
    async raw<T = any>(method: string, path: string, body?: unknown): Promise<Envelope<T>> {
        const headers: Record<string, string> = {};
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (this.token) headers['Authorization'] = this.token;
        const res = await this.ctx.fetch(`${API_BASE}${path}`, {
            method,
            headers,
            data: body === undefined ? undefined : JSON.stringify(body),
        });
        const text = await res.text();
        let json: any;
        try {
            json = text ? JSON.parse(text) : null;
        } catch {
            throw new Error(`[${method} ${path}] 非 JSON 响应(${res.status()}): ${text.slice(0, 300)}`);
        }
        // 空响应体（204 等）：视为业务成功
        if (json === null && res.ok()) {
            json = {code: 200, message: 'success', data: null};
        }
        return json as Envelope<T>;
    }

    /** 期望业务成功（code===200），否则抛异常；返回 data */
    async ok<T = any>(method: string, path: string, body?: unknown): Promise<T> {
        const env = await this.raw<T>(method, path, body);
        if (env.code !== 200) {
            throw new Error(
                `[${method} ${path}] 期望成功(code=200) 实际 code=${env.code} message=${env.message} data=${JSON.stringify(env.data)}`,
            );
        }
        return env.data;
    }

    get<T = any>(path: string): Promise<T> {
        return this.ok<T>('GET', path);
    }

    post<T = any>(path: string, body?: unknown): Promise<T> {
        return this.ok<T>('POST', path, body);
    }

    put<T = any>(path: string, body?: unknown): Promise<T> {
        return this.ok<T>('PUT', path, body);
    }

    del<T = any>(path: string): Promise<T> {
        return this.ok<T>('DELETE', path);
    }

    async dispose(): Promise<void> {
        await this.ctx.dispose();
    }
}
