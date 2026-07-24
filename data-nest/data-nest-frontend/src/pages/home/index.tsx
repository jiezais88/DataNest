import {useAuthStore} from '../../store/useAuthStore';

export default function HomePage() {
    const {userInfo} = useAuthStore();

    return (
        <div>
            <h1 className="text-ds-display text-ds-text-primary mb-ds-2">
                欢迎回来，{userInfo?.username}
            </h1>
            <p className="text-ds-body text-ds-text-secondary">
                DataNest 企业级数据中台 — 当前角色：{userInfo?.roles?.join('、') || '无'}
            </p>
        </div>
    );
}
