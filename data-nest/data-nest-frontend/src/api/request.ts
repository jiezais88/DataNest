import axios, {type AxiosError, type AxiosRequestConfig, type AxiosResponse} from 'axios';
import {notify} from '@/utils/notify';
import type {ApiError} from '@/utils/error';

declare module 'axios' {
    interface AxiosRequestConfig {
        /** 为 true 时拦截器不弹全局错误提示，由调用方自行处理（如 SQL 预览在行内展示错误） */
        skipErrorMessage?: boolean;
    }
}

const instance = axios.create({
    baseURL: '/api',
    timeout: 10000,
});

instance.interceptors.request.use((config) => {
    const token = localStorage.getItem('token');
    if (token) {
        config.headers.Authorization = token;
    }
    return config;
});

instance.interceptors.response.use(
    (res: AxiosResponse<unknown>) => {
        const data = res.data as { code?: number; message?: string } | null;
        if (data && typeof data === 'object' && 'code' in data && data.code !== 200) {
            const msg = data.message || '请求失败';
            // 3005 数据源存在引用时，由调用方弹窗展示详细引用列表，不显示通用错误提示
            if (data.code !== 3005 && !res.config.skipErrorMessage) {
                notify.error(msg);
            }
            const error: ApiError = new Error(msg);
            error.response = {data};
            return Promise.reject(error);
        }
        // 所有接口统一返回 {code, message, data} 信封，这里把 body 原样透传给调用方拆 data。
        // 拦截器类型签名要求返回 AxiosResponse，但运行时 axios 允许返回任意值，
        // 配合下方 request.get<T, T> 的第二泛型把类型修正回来，此处一次性受控强转。
        return data as unknown as AxiosResponse<unknown>;
    },
    (err: AxiosError<{ message?: string }>) => {
        if (err.response?.status === 401) {
            localStorage.removeItem('token');
            // 踢出登录（token 过期/闲置超时）：整页跳转前弹 toast 会被刷新销毁，
            // 改为带 expired=1 参数跳登录页，由登录页挂载时展示提示
            window.location.href = '/login?expired=1';
            return Promise.reject(err);
        }
        const msg = err.response?.data?.message || err.message || '网络异常，请稍后重试';
        if (!err.config?.skipErrorMessage) {
            notify.error(msg);
        }
        return Promise.reject(err);
    }
);

// 响应拦截器已把 AxiosResponse 拆成 body（Result 信封或裸数据），
// 用 axios 自带的 <T, R> 第二泛型把返回类型声明为 T，替代原来的 as unknown as 强转
const request = {
    get<T = unknown>(url: string, config?: AxiosRequestConfig) {
        return instance.get<T, T>(url, config);
    },
    post<T = unknown, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig<D>) {
        return instance.post<T, T>(url, data, config);
    },
    put<T = unknown, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig<D>) {
        return instance.put<T, T>(url, data, config);
    },
    delete<T = unknown>(url: string, config?: AxiosRequestConfig) {
        return instance.delete<T, T>(url, config);
    },
};

export default request;
