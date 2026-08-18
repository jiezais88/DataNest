import {create} from 'zustand';
import {getMe, type LoginUserInfo} from '@/api/auth';

const TOKEN_KEY = 'token';
const USER_INFO_KEY = 'datanest_user_info';

export type {LoginUserInfo as UserInfo};

interface UserInfo {
    userId: string;
    username: string;
    roles: string[];
    /** Sprint 11 F2：登录返回的按钮级权限点 code 列表（如 datasource:view） */
    permissions?: string[];
    /** Sprint 14：密码过期强制改密标记（改密完成后清除） */
    mustChangePwd?: boolean;
}

interface AuthState {
    token: string | null;
    userInfo: UserInfo | null;
    setAuth: (token: string, userInfo: UserInfo) => void;
    /** PM-14：进入应用时向后端拉取最新 roles/permissions 刷新快照（权限变更即时生效，无需重新登录） */
    refreshUserInfo: () => Promise<void>;
    logout: () => void;
}

const readStoredUserInfo = (): UserInfo | null => {
    try {
        const raw = localStorage.getItem(USER_INFO_KEY);
        if (!raw) return null;
        return JSON.parse(raw) as UserInfo;
    } catch {
        localStorage.removeItem(USER_INFO_KEY);
        return null;
    }
};

export const useAuthStore = create<AuthState>((set, get) => ({
    token: localStorage.getItem(TOKEN_KEY),
    userInfo: readStoredUserInfo(),
    setAuth: (token, userInfo) => {
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_INFO_KEY, JSON.stringify(userInfo));
        set({token, userInfo});
    },
    refreshUserInfo: async () => {
        if (!get().token) return;
        try {
            const res = await getMe();
            const info = res.data;
            if (!info) return;
            localStorage.setItem(USER_INFO_KEY, JSON.stringify(info));
            set({userInfo: info});
        } catch {
            // 刷新失败保持本地快照（登录态仍有效，下次进入再刷新）
        }
    },
    logout: () => {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_INFO_KEY);
        set({token: null, userInfo: null});
    },
}));
