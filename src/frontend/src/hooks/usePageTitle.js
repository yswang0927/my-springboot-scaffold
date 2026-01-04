import { useEffect } from 'react';

/**
 * 自定义 Hook：设置页面标题
 * @param {string} title - 要设置的标题
 * @param {boolean} restoreOnUnmount - 卸载时是否恢复原标题（默认 true）
 */
export default function usePageTitle(title, restoreOnUnmount = true) {
    useEffect(() => {
        const originalTitle = document.title;
        document.title = title;

        if (restoreOnUnmount) {
            return () => {
                document.title = originalTitle;
            };
        }
    }, [title, restoreOnUnmount]);
}