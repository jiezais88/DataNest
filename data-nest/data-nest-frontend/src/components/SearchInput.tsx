import {forwardRef, type InputHTMLAttributes} from 'react';
import {HiOutlineMagnifyingGlass} from 'react-icons/hi2';

export interface SearchInputProps
    extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onKeyDown'> {
    onEnter?: () => void;
    placeholder?: string;
}

const SearchInput = forwardRef<HTMLInputElement, SearchInputProps>(
    ({className = '', onEnter, placeholder = '搜索', ...rest}, ref) => {
        return (
            <div className={`relative flex-1 min-w-[240px] max-w-[360px] ${className}`}>
                <HiOutlineMagnifyingGlass
                    size={16}
                    className="absolute left-ds-3 top-1/2 -translate-y-1/2 text-ds-text-muted pointer-events-none"
                    aria-hidden="true"
                />
                <input
                    ref={ref}
                    onKeyDown={(e) => {
                        if (e.key === 'Enter') onEnter?.();
                    }}
                    aria-label="搜索"
                    placeholder={placeholder}
                    className="w-full pl-9 pr-[14px] py-[9px] bg-ds-bg-root border border-ds-border-subtle rounded-ds-sm text-sm text-ds-text-primary placeholder:text-ds-text-muted focus:outline-none focus:border-ds-accent focus:ring-[3px] focus:ring-ds-accent-glow transition-all duration-150"
                    {...rest}
                />
            </div>
        );
    },
);

SearchInput.displayName = 'SearchInput';

export default SearchInput;
