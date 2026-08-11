import axios from 'axios';

/**
 * 接口错误的统一类型与文案提取。历史背景：各页面 catch (e: any) 后直接读
 * e.response.data.message / e.message，靠 any 蒙混。收敛到这里后 catch 写
 * catch (e) 或 catch (e: unknown)，用 getErrorMessage(e) 取文案。
 */

/** request.ts 拦截器构造的业务错误（后端信封 code !== 200 时） */
export interface ApiError extends Error {
    response?: {
        data: {
            code?: number;
            message?: string;
            data?: unknown;
        };
    };
}

/** 从未知错误中提取可读文案：优先后端信封 message，其次 Error.message */
export function getErrorMessage(e: unknown, fallback = '操作失败，请稍后重试'): string {
    if (axios.isAxiosError(e)) {
        const data = e.response?.data as { message?: string } | undefined;
        return data?.message || e.message || fallback;
    }
    if (e instanceof Error) {
        const resp = (e as ApiError).response;
        return resp?.data?.message || e.message || fallback;
    }
    return fallback;
}

/** 从错误中提取后端业务错误码（信封 code，如 8008 作业丢失停止失败）；非业务错误返回 undefined */
export function getErrorCode(e: unknown): number | undefined {
    if (axios.isAxiosError(e)) {
        const data = e.response?.data as { code?: number } | undefined;
        return typeof data?.code === 'number' ? data.code : undefined;
    }
    if (e instanceof Error) {
        const resp = (e as ApiError).response;
        return typeof resp?.data?.code === 'number' ? resp.data.code : undefined;
    }
    return undefined;
}
