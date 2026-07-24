import {create} from 'zustand';

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

export const useAuthStore = create<AuthState>((set) => ({
    token: null,
    userInfo: null,
    setAuth: (token, userInfo) => {
        localStorage.setItem('token', token);
        set({token, userInfo});
    },
    logout: () => {
        localStorage.removeItem('token');
        set({token: null, userInfo: null});
    },
}));
