// @refresh reset
import { createContext, useState, useContext, useCallback } from "react";

import en from "../locales/en";

// 1. 聚合语言包
const dictionaries = {
    'zh-CN': {},
    'en': en
};

// 存储在 localStorage 中的 key 名称
const LANG_STORAGE_KEY = 'app_l10n_lang';

// 2. 创建 Context
const L10nContext = createContext();

// 3. 创建 Provider 组件
export function L10nProvider({ children, defaultLang = 'zh-CN' }) {
    const [lang, setLangState] = useState(() => {
        // 优先级 1: 尝试从 localStorage 获取
        const savedLang = window.localStorage.getItem(LANG_STORAGE_KEY);
        if (savedLang && dictionaries[savedLang]) {
            return savedLang;
        }

        // 优先级 2: 尝试获取浏览器语言
        // navigator.language 通常返回 'zh-CN', 'en-US', 'en' 等格式
        if (typeof navigator !== 'undefined' && navigator.language) {
            const browserLang = navigator.language;
            // 精确匹配 (如 'zh-CN')
            if (dictionaries[browserLang]) {
                return browserLang;
            }
            // 模糊匹配: 如果浏览器是 'en-US'，但我们只有 'en'，则尝试匹配前缀
            const shortLang = browserLang.split('-')[0];
            if (dictionaries[shortLang]) {
                return shortLang;
            }
        }
        // 优先级 3: 兜底语言
        return defaultLang;
    });

    // 暴露给外部的切换语言方法
    const setLang = (newLang) => {
        if (dictionaries[newLang]) {
            setLangState(newLang);
            window.localStorage.setItem(LANG_STORAGE_KEY, newLang);
        } else {
            console.warn(`Language ${newLang} not found.`);
        }
    };

    // 核心翻译函数
    const t = useCallback((key, args = {}) => {
        const dict = dictionaries[lang];
        // 如果找不到对应的翻译，降级显示 key 本身
        let text = dict[key] || key;

        // 处理变量插值 (例如: {name})
        if (Object.keys(args).length > 0) {
            Object.entries(args).forEach(([argKey, argVal]) => {
                // 使用正则全局替换对应的占位符
                const regex = new RegExp(`\\{${argKey}\\}`, 'g');
                text = text.replace(regex, argVal);
            });
        }

        return text;
    }, [lang]);

    return (
        <L10nContext.Provider value={{ t, lang, setLang }}>
            {children}
        </L10nContext.Provider>
    );
}

// 4. 导出自定义 Hook，让组件使用
export function useL10n() {
    const context = useContext(L10nContext);
    if (!context) {
        throw new Error('useL10n must be used within a L10nProvider');
    }
    return context;
}