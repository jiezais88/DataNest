import {useNavigate} from 'react-router-dom';
import {HiChevronRight} from 'react-icons/hi2';
import {resolveBreadcrumb} from '../utils/breadcrumb';

type Props = {
    pathname: string;
};

/**
 * Top-of-page breadcrumb nav. Renders nothing for the root path "/".
 * Each non-current segment is clickable and uses useNavigate to jump
 * to the segment's path. The current (last) segment is rendered as a
 * non-interactive span styled as the "current" item per DESIGN §4.16.
 */
export default function Breadcrumb({pathname}: Props) {
    const navigate = useNavigate();
    const segments = resolveBreadcrumb(pathname);

    if (segments.length === 0) {
        return null;
    }

    return (
        <nav
            aria-label="breadcrumb"
            data-testid="ds-breadcrumb"
            className="flex items-center gap-2 text-ds-small text-ds-text-muted mb-5"
        >
            {segments.map((seg, idx) => {
                const isLast = idx === segments.length - 1;
                return (
                    <span
                        key={seg.path}
                        className="flex items-center gap-2"
                    >
                        {isLast ? (
                            <span
                                aria-current="page"
                                className="text-ds-text-secondary font-medium"
                            >
                                {seg.label}
                            </span>
                        ) : (
                            <button
                                type="button"
                                onClick={() => navigate(seg.path)}
                                className="text-ds-accent cursor-pointer hover:underline bg-transparent border-0 p-0 text-ds-small leading-none"
                            >
                                {seg.label}
                            </button>
                        )}
                        {!isLast && (
                            <HiChevronRight
                                size={14}
                                className="text-ds-border-strong"
                                aria-hidden="true"
                            />
                        )}
                    </span>
                );
            })}
        </nav>
    );
}
