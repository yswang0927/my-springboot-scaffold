import { $nodeSchema, $remark, $view } from '@milkdown/utils';
import remarkFrontmatter from 'remark-frontmatter';
import yaml from 'js-yaml';

export const remarkFrontmatterPlugin = $remark('remarkFrontmatter', () => {
    return function () {
        // 这里的 this 会自动指向 Milkdown 底层的 Unified processor
        return remarkFrontmatter.call(this, ['yaml']);
    };
});

// 2. 定义节点 Schema (提取数据模型)
export const aiSkillsMetadataSchema = $nodeSchema('aiSkillsMetadata', () => ({
    group: 'block',
    atom: true, // 作为一个不可分割的整体原子节点
    isolating: true,
    attrs: {
        rawYaml: { default: '' },
        yamlData: { default: {} }
    },
    parseMarkdown: {
        match: (node) => {
            return node.type === 'yaml';
        },
        runner: (state, node, type) => {
            const rawYaml = node.value;
            let yamlData = {};
            try {
                yamlData = yaml.load(rawYaml) || {};
            } catch (error) {
                console.error('YAML 解析失败:', error);
            }
            state.addNode(type, { rawYaml, yamlData });
        }
    },
    toMarkdown: {
        match: (node) => node.type.name === 'aiSkillsMetadata',
        runner: (state, node) => {
            // 序列化时，将最新的 rawYaml 写回 Markdown
            state.addNode('yaml', undefined, node.attrs.rawYaml);
        }
    },
    parseDOM: [{
        tag: 'div[data-type="ai-skills-metadata"]',
        getAttrs: () => ({})
    }],
    // toDOM 主要是为了提供一个保底的占位结构，实际渲染由下面的 NodeView 接管
    toDOM: () => ['div', { 'data-type': 'ai-skills-metadata' }, 'Metadata Table']
}));

// 3. 创建交互式 NodeView
export const aiSkillsMetadataView = $view(aiSkillsMetadataSchema.node, () => {
    return (node, view, getPos) => {
        // 创建最外层容器
        const dom = document.createElement('div');
        dom.setAttribute('data-type', 'ai-skills-metadata');
        dom.className = 'ai-skills-metadata-container';
        dom.style.margin = '1.5rem 0';
        dom.style.fontFamily = 'sans-serif';

        // 渲染/重新渲染表格的函数
        const renderTable = () => {
            dom.innerHTML = ''; // 清空容器
            const table = document.createElement('table');
            table.style.width = '100%';
            table.style.borderCollapse = 'collapse';
            table.style.textAlign = 'left';

            const yamlData = node.attrs.yamlData || {};

            Object.entries(yamlData).forEach(([key, value]) => {
                const tr = document.createElement('tr');
                // 属性名列 (不可编辑)
                const tdKey = document.createElement('th');
                tdKey.textContent = key;
                tdKey.style.fontWeight = '600';
                tdKey.style.padding = '8px';
                tdKey.style.border = '1px solid #ddd';
                tdKey.style.backgroundColor = '#f9fafb';
                tdKey.style.width = '30%';

                // 属性值列 (可双击编辑)
                const tdValue = document.createElement('td');
                tdValue.textContent = value;
                tdValue.style.padding = '8px';
                tdValue.style.border = '1px solid #ddd';
                tdValue.style.width = '70%';
                tdValue.style.cursor = 'text'; // 提示用户可点击

                // 绑定双击事件
                tdValue.addEventListener('dblclick', () => {
                    // 创建输入框
                    const input = document.createElement('input');
                    input.type = 'text';
                    input.value = value;
                    input.style.width = '100%';
                    input.style.boxSizing = 'border-box';
                    input.style.padding = '4px';
                    input.style.outline = 'none';
                    input.style.border = '1px solid #3b82f6'; // 聚焦时的边框色

                    // 保存数据的逻辑
                    const save = () => {
                        const newValue = input.value;
                        if (newValue !== value) {
                            // 1. 构建新的数据对象
                            const newData = { ...yamlData, [key]: newValue };
                            // 2. 将对象转回 YAML 字符串格式
                            const newRawYaml = yaml.dump(newData, {lineWidth: -1});
                            // 3. 向 ProseMirror 派发事务，更新节点属性
                            const tr = view.state.tr.setNodeMarkup(getPos(), undefined, {
                                rawYaml: newRawYaml.trim(), // 去除末尾多余换行
                                yamlData: newData
                            });
                            view.dispatch(tr);
                        } else {
                            // 如果值没变，恢复原始显示
                            renderTable();
                        }
                    };

                    // 绑定保存事件
                    input.addEventListener('blur', save);
                    input.addEventListener('keydown', (e) => {
                        if (e.key === 'Enter') {
                            input.blur(); // 触发 blur 保存
                        } else if (e.key === 'Escape') {
                            renderTable(); // 取消编辑
                        }
                    });

                    // 切换 DOM 为输入框并聚焦
                    tdValue.innerHTML = '';
                    tdValue.appendChild(input);
                    input.focus();
                });

                tr.appendChild(tdKey);
                tr.appendChild(tdValue);
                table.appendChild(tr);
            });

            dom.appendChild(table);
        };

        // 初次渲染
        renderTable();

        return {
            dom,
            // 当节点属性改变时（例如我们派发了 transaction），会触发 update
            update: (updatedNode) => {
                if (updatedNode.type.name !== 'aiSkillsMetadata') return false;
                node = updatedNode; // 更新闭包中的 node 引用
                renderTable();      // 重新渲染 UI
                return true;
            },
            // 【关键】阻止 ProseMirror 捕获输入框里的事件，防止输入内容时触发编辑器的其他快捷键
            stopEvent: (e) => {
                return e.target.tagName === 'INPUT';
            }
        };
    };
});

// 4. 导出组合后的插件数组
export const aiSkillsMetadataPlugin = [
    remarkFrontmatterPlugin,
    aiSkillsMetadataSchema,
    aiSkillsMetadataView
];