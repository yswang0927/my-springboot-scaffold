import { useEffect, useRef, useCallback, useState } from "react";
import { Crepe } from "@milkdown/crepe";
import { listenerCtx } from '@milkdown/kit/plugin/listener'
import { editorViewCtx, parserCtx } from "@milkdown/kit/core"
import { Selection } from "@milkdown/kit/prose/state";
import { Slice } from "@milkdown/kit/prose/model";
import mermaid from "mermaid";
import { v4 as uuidv4 } from "uuid"
import { aiSkillsMetadataPlugin } from "./skill"

import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";

try {
    mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'loose',
        fontSize: 14,
        theme: 'default',
        look: 'classic'
    });
} catch(e) {
    console.error('>>> Error: mermaid initialize failed: ', e);
}

// mermaid代码块渲染为mermaid图表
const renderPreviewMermaidCode = (content, applyView) => {
    if (!content) {
        return null;
    }

    const containerId = `mermaid-preview-container-${uuidv4()}`;
    const container = document.createElement('div');
    container.className = "mermaid-preview-container";
    container.setAttribute('id', containerId);

    const placeholderDomId = `mermaid-preview-${uuidv4()}`;
    const placeholderDom = document.createElement('div');
    placeholderDom.className = "mermaid-preview";
    placeholderDom.setAttribute('id', placeholderDomId);
    container.appendChild(placeholderDom);

    requestAnimationFrame(()=>{
        mermaid.render(placeholderDomId, content)
            .then(({ svg, bindFunctions }) => {
                const santifiedContainer = document.getElementById(containerId);
                if (!santifiedContainer) {
                    applyView(null);
                    return;
                }
                santifiedContainer.innerHTML = svg;
                bindFunctions?.(santifiedContainer);
            }).catch((error) => {
            const errMsg = error instanceof Error ? error.message : String(error);
            // Mermaid 报错时可能会在 body 中残留错误标签，这里做个清理
            document.querySelectorAll(`div[id^="dmermaid-"]`).forEach(el => el.remove());
            const santifiedContainer = document.getElementById(containerId);
            if (santifiedContainer) {
                santifiedContainer.innerHTML = `<pre class="milkdown-code-preview-error">${errMsg}</pre>`;
            } else {
                applyView(null);
            }
        });
    });

    return container;
};

export default function MarkdownEditor({value}) {
    const editorDomRef = useRef(null);
    const crepeRef = useRef(null);
    const [initialized, setInitialized] = useState(false);

    // 记录上一次外部传入的 value，避免重复 setContent
    const lastExternalValueRef = useRef(value);
    // 标记当前变更是否来自编辑器内部，防止循环
    const isInternalUpdate = useRef(false);
    const timerRef = useRef(null); // 用于防抖

    useEffect(() => {
        if (initialized) {
            return;
        }

        if (!editorDomRef.current) {
            return;
        }

        const crepe = new Crepe({
            root: editorDomRef.current,
            defaultValue: value || "",
            featureConfigs: {
                [Crepe.Feature.Cursor]: {
                    virtual: false
                },
                [Crepe.Feature.CodeMirror]: {
                    noResultText: '无结果',
                    copyText: '复制',
                    searchPlaceholder: '搜索语言',
                    previewToggleText: (previewOnlyMode) => (previewOnlyMode ? '编辑' : '隐藏'),
                    previewLabel: () => '预览',
                    previewOnlyByDefault: true,
                    previewLoading: '渲染中...',
                    renderPreview: (language, content, applyView) => {
                        const codeLang = language ? language.toLowerCase() : "";
                        // mermaid图
                        if (codeLang === 'mermaid' && content.length > 0) {
                            return renderPreviewMermaidCode(content, applyView);
                        }

                        return null;
                    },
                    onCopy: () => {}
                },
                [Crepe.Feature.LinkTooltip]: {
                    inputPlaceholder: '输入链接地址',
                    onCopyLink: () => {
                    }
                },
                [Crepe.Feature.Placeholder]: {
                    text: '输入 “ / ” 可插入内容'
                },
                [Crepe.Feature.BlockEdit]: {
                    textGroup: {
                        label: '文本',
                        text: {label: '正文'},
                        h1: {label: '一级标题'},
                        h2: {label: '二级标题'},
                        h3: {label: '三级标题'},
                        h4: {label: '四级标题'},
                        h5: {label: '五级标题'},
                        h6: {label: '六级标题'},
                        quote: {label: '引用'},
                        divider: {label: '分割线'},
                    },
                    listGroup: {
                        label: '列表',
                        bulletList: {label: '无序列表'},
                        orderedList: {label: '有序列表'},
                        taskList: {label: '任务列表'},
                    },
                    advancedGroup: {
                        label: '高级',
                        image: {label: '图片'},
                        codeBlock: {label: '代码块'},
                        table: {label: '表格'},
                        math: {label: '数学公式'},
                    }
                },
                [Crepe.Feature.ImageBlock]: {
                    blockCaptionPlaceholderText: '添加图片描述',
                    blockUploadButton: () => '上传图片',
                    blockUploadPlaceholderText: '或 粘贴图片URL地址',
                    inlineUploadPlaceholderText: '或 粘贴图片URL地址',
                    inlineUploadButton: () => '上传图片',
                    blockConfirmButton: () => '确定',
                    inlineConfirmButton: () => '确定',
                    inlineImageIcon: () => '🖼️',
                    blockImageIcon: () => '🖼️'
                }
            }
        });

        //crepe.setReadonly(true);

        crepe.editor.use(aiSkillsMetadataPlugin);

        crepe.editor.config((ctx) => {
            // 监听事件
            const listener = ctx.get(listenerCtx);

            listener.markdownUpdated((_, markdown, prevMarkdown) => {
                console.log(">>> new-markdown: \n"+markdown);
                if (markdown === prevMarkdown) {
                    return;
                }
                // 如果这是由 replaceAll 触发的更新，直接跳过，不反向调用 setValue
                if (isInternalUpdate.current) {
                    isInternalUpdate.current = false;
                    return;
                }

                // 编辑器内产生变化，通知外部
                lastExternalValueRef.current = markdown;
            });
        });

        crepe.create().then(() => {
            setInitialized(true);
            crepeRef.current = crepe;
            console.log(">>> Crepe created");
        });

        return () => {
            if (timerRef.current !== null) {
                clearTimeout(timerRef.current);
            }

            crepe.destroy();
            console.log(">>> Crepe destroy");
        };
    }, []);

    const setContent = useCallback((value, autoScroll = false) => {
        if (!crepeRef.current) return;
        crepeRef.current.editor.action((ctx) => {
            const s = Date.now();
            const view = ctx.get(editorViewCtx);
            const parser = ctx.get(parserCtx);
            const doc = parser(value);
            if (!doc) return;
            const state = view.state;
            const selection = state.selection;
            let tr = state.tr;
            tr = tr.replace(0, state.doc.content.size, new Slice(doc.content, 0, 0));
            const docSize = doc.content.size;
            const safeFrom = Math.min(selection.from, docSize - 2);
            tr = tr.setSelection(Selection.near(tr.doc.resolve(safeFrom)));
            view.dispatch(tr);

            if (view.dom.parentElement) {
                view.dom.parentElement.scrollTop = autoScroll ? view.dom.parentElement.scrollHeight : 0;
            }

            const cost = Date.now() - s;
            console.warn(`>> 警告: milkdown parser() 耗时: ${cost} ms`);
        });
    }, [crepeRef.current]);

    // 监听外部 value 变化，同步给编辑器
    useEffect(() => {
        // 如果外部传入的值跟我们最后记录的值一致，说明：
        // 1. 这是编辑器自己触发 setValue 后，React 重新 render 传回来的旧值
        // 2. 或者是内容没变。 此时不需要 replaceAll。
        if (value === lastExternalValueRef.current) return;

        lastExternalValueRef.current = value;
        // 开启锁，告知 listener 这是受控更新，不要触发反向同步
        isInternalUpdate.current = true;
        setContent(value, false);
    }, [value, setContent]);

    return (
        <div className="relative h-full">
            <div className="absolute inset-0">
                <div ref={editorDomRef}
                     className="absolute inset-0 mdeditor-container"
                ></div>
            </div>
        </div>
    );
};
