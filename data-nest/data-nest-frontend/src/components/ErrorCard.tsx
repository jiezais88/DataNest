import {useEffect} from 'react';
import {HiOutlineXCircle} from 'react-icons/hi2';

interface Props {
    message: string;
    onClose: () => void;
}

export default function ErrorCard({message, onClose}: Props) {
    useEffect(() => {
        const timer = setTimeout(onClose, 5000);
        return () => clearTimeout(timer);
    }, [onClose]);

    return (
        <div
            className="bg-ds-danger-light border border-ds-danger/20 rounded-ds-sm px-ds-3 py-ds-2 flex items-center gap-ds-2 animate-in slide-in-from-top-2">
            <HiOutlineXCircle size={18} className="text-ds-danger shrink-0"/>
            <span className="text-ds-small text-ds-danger flex-1">{message}</span>
            <button onClick={onClose} className="text-ds-danger/60 hover:text-ds-danger">
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                    <path d="M1 1l12 12M13 1L1 13" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
                </svg>
            </button>
        </div>
    );
}
