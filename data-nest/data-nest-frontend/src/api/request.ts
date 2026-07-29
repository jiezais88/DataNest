import axios, {type AxiosResponse} from 'axios';
import {message} from 'antd';

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
    (res: AxiosResponse<any>) => {
        const data = res.data;
        if (data && typeof data === 'object' && 'code' in data && data.code !== 200) {
            const msg = data.message || '请求失败';
            // 3005 数据源存在引用时，由调用方弹窗展示详细引用列表，不显示通用错误提示
            if (data.code !== 3005) {
                message.error(msg);
            }
            const error = new Error(msg) as any;
            error.response = {data};
            return Promise.reject(error);
        }
        return data;
    },
    (err) => {
        if (err.response?.status === 401) {
            localStorage.removeItem('token');
            window.location.href = '/login';
            return Promise.reject(err);
        }
        const msg = err.response?.data?.message || err.message || '网络异常，请稍后重试';
        message.error(msg);
        return Promise.reject(err);
    }
);

const request = instance as unknown as {
    get<T = any>(url: string, config?: any): Promise<T>;
    post<T = any>(url: string, data?: any, config?: any): Promise<T>;
    put<T = any>(url: string, data?: any, config?: any): Promise<T>;
    delete<T = any>(url: string, config?: any): Promise<T>;
};

export default request;
