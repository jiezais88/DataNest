import {useEffect, useRef} from 'react';
import {HiOutlineClipboardDocument, HiOutlineXMark} from 'react-icons/hi2';

interface CodeLogProps {
    open: boolean;
    title: string;
    lines: { level: string; message: string }[];
    onClose: () => void;
}

export default function CodeLog({open, title, lines, onClose}: CodeLogProps) {
    const bottomRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (open && bottomRef.current) {
            bottomRef.current.scrollIntoView({behavior: 'smooth'});
        }
    }, [open, lines]);

    if (!open) return null;

    const handleCopy = () => {
        const text = lines.map((l) => `[${l.level}] ${l.message}`).join('\n');
        navigator.clipboard.writeText(text).catch(() => null);
    };

    const levelClass = (level: string) => {
        if (level === 'ERROR') return 'text-red-400';
        if (level === 'WARN') return 'text-amber-400';
        return 'text-slate-300';
    };

    return (
        <div className="fixed inset-0 z-ds-dialog flex items-center justify-center p-ds-6">
            <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={onClose}/>
            <div
                className="relative w-full max-w-[960px] h-[80vh] bg-slate-900 rounded-ds-md shadow-ds-xl flex flex-col overflow-hidden">
                <div
                    className="flex items-center justify-between px-ds-5 py-ds-3 border-b border-slate-700 flex-shrink-0">
                    <h3 className="text-ds-subhead text-white font-semibold">{title}</h3>
                    <div className="flex items-center gap-ds-2">
                        <button
                            onClick={handleCopy}
                            className="flex items-center gap-ds-1 px-ds-3 py-ds-1.5 text-ds-small text-slate-300 hover:text-white hover:bg-slate-700 rounded-ds-sm transition-colors"
                        >
                            <HiOutlineClipboardDocument size={16}/>
                            复制
                        </button>
                        <button
                            onClick={onClose}
                            className="p-1.5 text-slate-400 hover:text-white hover:bg-slate-700 rounded transition-colors"
                            aria-label="关闭"
                        >
                            <HiOutlineXMark size={20}/>
                        </button>
                    </div>
                </div>

                <div className="flex-1 overflow-auto p-ds-4 font-mono text-ds-small leading-relaxed">
                    {lines.length === 0 && (
                        <p className="text-slate-500 italic">暂无日志</p>
                    )}
                    {lines.map((line, index) => (
                        <div key={index} className="break-words">
                            <span className="text-slate-500 mr-2">{index + 1}</span>
                            <span className={levelClass(line.level)}>[{line.level}]</span>
                            <span className="text-slate-300 ml-2">{line.message}</span>
                        </div>
                    ))}
                    <div ref={bottomRef}/>
                </div>
            </div>
        </div>
    );
}
