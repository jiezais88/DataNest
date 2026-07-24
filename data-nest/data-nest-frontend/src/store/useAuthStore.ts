import {create} from 'zustand';

const TOKEN_KEY = 'token';
const USER_INFO_KEY = 'datanest_user_info';

interface UserInfo {
    userId: string;
    username: string;
    roles: string[];
}

interface AuthState {
    token: string | null;
    userInfo: UserInfo | null;
    setAuth: (token: string, userInfo: UserInfo) => void;
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

export const useAuthStore = create<AuthState>((set) => ({
    token: localStorage.getItem(TOKEN_KEY),
    userInfo: readStoredUserInfo(),
    setAuth: (token, userInfo) => {
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_INFO_KEY, JSON.stringify(userInfo));
        set({token, userInfo});
    },
    logout: () => {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_INFO_KEY);
        set({token: null, userInfo: null});
    },
}));
