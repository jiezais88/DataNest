/**
 * 后端通用协议类型（单一出处）。历史背景：Result/PageResult 曾在 api/datasource.ts
 * 定义后被 dags/api.ts 反向 import，还有 5 处 {code, message} 内联复写。收敛到这里。
 */

/** 后端统一响应信封 {code, message, data} */
export interface Result<T> {
    code: number;
    message?: string;
    data: T;
}

/** 与后端 com.datanest.common.model.PageResult 对齐 */
export interface PageResult<T> {
    records: T[];
    total: number;
    page: number;
    pageSize: number;
}

/** 分页查询参数（配合 usePagedList 的 fetcher 签名） */
export interface PagedQuery {
    page?: number;
    pageSize?: number;
}
