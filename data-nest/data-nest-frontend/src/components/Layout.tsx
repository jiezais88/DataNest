import {Outlet, useNavigate} from 'react-router-dom';
import {useState} from 'react';
import Sidebar from './Sidebar';
import {useAuthStore} from '../store/useAuthStore';
import ChangePasswordModal from './ChangePasswordModal';
import {HiOutlineArrowRightOnRectangle, HiOutlineLockClosed} from 'react-icons/hi2';

export default function Layout() {
    const {userInfo, logout} = useAuthStore();
    const navigate = useNavigate();
    const [menuOpen, setMenuOpen] = useState(false);
    const [pwdModalOpen, setPwdModalOpen] = useState(false);

    const handleLogout = () => {
        logout();
        navigate('/login');
    };

    const initials = userInfo?.username?.slice(0, 1).toUpperCase() || 'U';

    return (
        <div className="h-screen overflow-hidden bg-ds-bg-root">
            <Sidebar/>

            {/* Main content area */}
            <div className="ml-[248px] flex flex-col h-screen">
                {/* Top bar */}
                <header
                    className="flex-shrink-0 z-ds-elevated h-14 bg-ds-bg-surface/80 backdrop-blur-sm border-b border-ds-border-subtle flex items-center justify-end px-ds-6">
                    <div className="relative">
                        <button
                            onClick={() => setMenuOpen(!menuOpen)}
                            className="flex items-center gap-ds-2 px-ds-2 py-ds-1 rounded-ds-sm hover:bg-ds-bg-hover transition-colors ds-fast"
                        >
                            <div className="w-7 h-7 rounded-full bg-ds-accent flex items-center justify-center">
                                <span className="text-white text-xs font-bold">{initials}</span>
                            </div>
                            <span className="text-ds-small text-ds-text-secondary">{userInfo?.username}</span>
                        </button>

                        {menuOpen && (
                            <>
                                <div className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)}/>
                                <div
                                    className="absolute right-0 top-full mt-1 w-40 bg-ds-bg-surface rounded-ds-sm shadow-ds-lg border border-ds-border-subtle py-1 z-20 animate-in fade-in">
                                    <button
                                        onClick={() => {
                                            setMenuOpen(false);
                                            setPwdModalOpen(true);
                                        }}
                                        className="w-full flex items-center gap-ds-2 px-ds-3 py-ds-2 text-ds-small text-ds-text-secondary hover:bg-ds-bg-hover transition-colors"
                                    >
                                        <HiOutlineLockClosed size={16}/>
                                        修改密码
                                    </button>
                                    <button
                                        onClick={handleLogout}
                                        className="w-full flex items-center gap-ds-2 px-ds-3 py-ds-2 text-ds-small text-ds-danger hover:bg-ds-danger-light transition-colors"
                                    >
                                        <HiOutlineArrowRightOnRectangle size={16}/>
                                        退出登录
                                    </button>
                                </div>
                            </>
                        )}
                    </div>
                </header>

                {/* Page content */}
                <main className="flex-1 min-h-0 overflow-hidden p-ds-6">
                    <Outlet/>
                </main>
            </div>

            <ChangePasswordModal open={pwdModalOpen} onClose={() => setPwdModalOpen(false)}/>
        </div>
    );
}
