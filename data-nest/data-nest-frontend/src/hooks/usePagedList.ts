import {useCallback, useEffect, useRef, useState} from 'react';

/**
 * 列表页统一的分页 + 查询 Hook。历史背景：9 个列表页各自手写
 * list/total/page/pageSize/loading/appliedQuery 六七份 state 加 load/useEffect，
 * 还有 searchTrigger、applied 对象、前端假分页三种查询变体。现在收敛到这里：
 * 草稿查询条件仍由页面自己持有（表单输入），本 Hook 管「已应用」查询 + 分页 + 加载。
 *
 * 用法：
 *   const {list, total, page, pageSize, loading, setPage, setPageSize, applyQuery, reload} =
 *       usePagedList({fetcher, initialQuery: {}, defaultPageSize: 10});
 *   // 查询按钮：applyQuery(draft)；重置按钮：applyQuery(INITIAL_QUERY)；增删改后：reload()
 */
export interface PagedResult<T> {
    list: T[];
    total: number;
}

export interface UsePagedListOptions<Q extends object, T> {
    /** 适配各接口返回结构（records/list、total/totalCount 等），统一转成 {list, total} */
    fetcher: (query: Q & { page: number; pageSize: number }) => Promise<PagedResult<T>>;
    /** 「已应用」查询条件的初始值（重置时也用它） */
    initialQuery: Q;
    /** 默认每页条数，默认 10 */
    defaultPageSize?: number;
}

export interface UsePagedListResult<Q extends object, T> {
    list: T[];
    total: number;
    page: number;
    pageSize: number;
    loading: boolean;
    /** 当前已应用的查询条件 */
    query: Q;
    setPage: (page: number) => void;
    /** 改每页条数并回到第 1 页 */
    setPageSize: (pageSize: number) => void;
    /** 应用新查询条件并回到第 1 页（查询/重置按钮都用它） */
    applyQuery: (query: Q) => void;
    /** 以当前条件重新加载（增删改之后用） */
    reload: () => void;
}

export default function usePagedList<Q extends object, T>({
                                                              fetcher,
                                                              initialQuery,
                                                              defaultPageSize = 10,
                                                          }: UsePagedListOptions<Q, T>): UsePagedListResult<Q, T> {
    const [list, setList] = useState<T[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSizeState] = useState(defaultPageSize);
    const [loading, setLoading] = useState(false);
    const [query, setQuery] = useState<Q>(initialQuery);
    const [reloadFlag, setReloadFlag] = useState(0);

    // fetcher 每次渲染都是新引用，用 ref 避免它进入 effect 依赖导致死循环
    const fetcherRef = useRef(fetcher);
    fetcherRef.current = fetcher;

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        fetcherRef.current({...query, page, pageSize})
            .then(res => {
                if (cancelled) return;
                setList(res.list);
                setTotal(res.total);
            })
            .catch(() => {
                // 错误提示由 request 拦截器统一弹出
                if (cancelled) return;
                setList([]);
                setTotal(0);
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [query, page, pageSize, reloadFlag]);

    const setPageSize = useCallback((next: number) => {
        setPageSizeState(next);
        setPage(1);
    }, []);

    const applyQuery = useCallback((next: Q) => {
        setQuery(next);
        setPage(1);
    }, []);

    const reload = useCallback(() => setReloadFlag(flag => flag + 1), []);

    return {list, total, page, pageSize, loading, query, setPage, setPageSize, applyQuery, reload};
}
