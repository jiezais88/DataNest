import {useEffect} from 'react';

export function useDocumentTitle(title: string) {
    useEffect(() => {
        const prev = document.title;
        document.title = title ? `DataNest — ${title}` : 'DataNest';
        return () => {
            document.title = prev;
        };
    }, [title]);
}
