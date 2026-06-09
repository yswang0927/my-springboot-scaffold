/*!
 * 提供一些在布局处理上的通用函数, 比如: LayoutResizer 等
 */

/**
 * 将多个url片段拼接为一个完整的url, 准确处理掉多余的 /.
 * 示例:
 * joinUrl('/a', '/b/', 'c') => /a/b/c
 * joinUrl('/a', '../b/', './c') => /b/c
 * joinUrl('a', 'b', 'c?a=1&b=2#f1') => a/b/c?a=1&b=2#f1
 * joinUrl('http://127.0.0.1:8080/api', 'v1/files') => http://127.0.0.1:8080/api/v1/files
 *
 * @returns 完整的符合URL标准的url串
 */
export const joinUrl = function() {
    const parts = Array.from(arguments);
    const joined = parts
        .filter(p => typeof p === 'string' && p.trim() !== '')
        .join('/')
        .replace(/([^:])\/{2,}/g, '$1/')
        // 处理前导 // (协议相对路径)
        .replace(/^\/\//, '§§')   // 临时占位
        .replace(/\/{2,}/g, '/')  // 已被上一条处理，这里做双保险
        .replace(/^§§/, '//');

    if (!joined) return '';

    // 利用 URL 对象来处理路径中可能出现的相对路径(./ ../)
    // 准备一个虚拟域名，用来骗过 new URL() 处理纯相对路径的情况
    const DUMMY_BASE = 'http://__dummy_domain__.local';
    try {
        const url = new URL(joined, DUMMY_BASE);
        // 如果解析后的 origin 是我们的虚拟域名，说明拼接的是纯相对路径
        if (url.origin === DUMMY_BASE) {
            // 把虚拟域名剥离出去，只返回路径、查询参数和哈希
            let result = url.pathname + url.search + url.hash;
            // 还原纯相对路径没有前导斜杠的情况 (例如输入 "a", "b" 返回 "a/b" 而不是 "/a/b")
            if (!joined.startsWith('/') && result.startsWith('/')) {
                result = result.slice(1);
            }
            return result;
        }

        return url.href;

    } catch (e) {
        // 兜底
        return joined;
    }
};

/**
 * 布局resize通用函数, 用于拖动手柄resize 左|右|上|下 区域的大小.
 *
 * 示例1(拖动左侧区域改变大小):
 * ```
 * <div class="flex flex-row layout1">
 *  <div style="width: 200px" class="relative">
 *      <div class="layout-resizer" data-region="left" data-min="60" data-max="500"></div>
 *  </div>
 *  <div class="flex-1">Right</div>
 * </div>
 *
 * new LayoutResizer({
 *  trigger: document.querySelector('.layout1 .layout-resizer'),
 *  onResizing: (w) => {
 *      document.querySelector('.layout1 .layout-resizer').parentElement.style.width = w + 'px';
 *  }
 * });
 * ```
 *
 * 示例2(拖动右侧区域改变大小):
 * ```
 * <div class="flex flex-row layout2">
 *  <div class="flex-1">Left</div>
 *  <div style="width: 200px" class="relative">
 *      <div class="layout-resizer" data-region="right" data-min="60" data-max="500"></div>
 *  </div>
 * </div>
 *
 * new LayoutResizer({
 *  trigger: document.querySelector('.layout2 .layout-resizer'),
 *  onResizing: (w) => {
 *      document.querySelector('.layout2 .layout-resizer').parentElement.style.width = w + 'px';
 *  }
 * });
 * ```
 *
 * 示例3(上下参照示例1,2类似: data-region="top|bottom").
 */
class LayoutResizer {
    constructor(options) {
        this.trigger = options.trigger;
        this.onResizing = options.onResizing || (() => {});
        this.onResizeStart = options.onResizeStart || (() => {});
        this.onResizeEnd = options.onResizeEnd || (() => {});

        if (!this.trigger) {
            console.warn('LayoutResizer: 未找到触发拖拽的 DOM 元素。');
            return;
        }

        // 1. 获取区域，默认为左侧
        this.region = this.trigger.getAttribute('data-region') || 'left';
        this.dir = ['top', 'bottom'].includes(this.region) ? 'vertical' : 'horizontal';

        // 3. 读取范围限制
        this.min = options.min ?? Number(this.trigger.getAttribute('data-min')) ?? 0;
        this.max = options.max ?? Number(this.trigger.getAttribute('data-max')) ?? Infinity;

        // 绑定上下文
        this._handleMouseDown = this._handleMouseDown.bind(this);
        this._handleMouseMove = this._handleMouseMove.bind(this);
        this._handleMouseUp = this._handleMouseUp.bind(this);

        this._maskElement = null;

        this.init();
    }

    init() {
        this.trigger.addEventListener('mousedown', this._handleMouseDown);
    }

    _handleMouseDown(e) {
        e.preventDefault();
        this.startX = e.clientX;
        this.startY = e.clientY;

        const rect = this.trigger.parentElement.getBoundingClientRect();
        this.startWidth = rect.width;
        this.startHeight = rect.height;

        this._createMask();
        this.onResizeStart();

        document.body.style.userSelect = 'none';

        window.addEventListener('mousemove', this._handleMouseMove);
        window.addEventListener('mouseup', this._handleMouseUp);
    }

    _handleMouseMove(e) {
        let currentSize;

        if (this.dir === 'horizontal') {
            const deltaX = e.clientX - this.startX;
            // right 减，left 加
            currentSize = this.region === 'right' ? this.startWidth - deltaX : this.startWidth + deltaX;
        } else {
            const deltaY = e.clientY - this.startY;
            // bottom 减，top 加
            currentSize = this.region === 'bottom' ? this.startHeight - deltaY : this.startHeight + deltaY;
        }

        // 边界限制
        currentSize = Math.max(this.min, Math.min(this.max, currentSize));

        if (typeof this.onResizing === 'function') {
            this.onResizing(currentSize, this.region);
        }
    }

    _handleMouseUp() {
        this._removeMask();
        this.onResizeEnd();
        document.body.style.userSelect = '';
        window.removeEventListener('mousemove', this._handleMouseMove);
        window.removeEventListener('mouseup', this._handleMouseUp);
    }

    // 创建全屏透明遮罩
    _createMask() {
        const mask = document.createElement('div');
        Object.assign(mask.style, {
            position: 'fixed',
            top: 0,
            left: 0,
            width: '100vw',
            height: '100vh',
            zIndex: 999999, // 确保在最上层，挡住 iframe 和其他业务组件
            backgroundColor: 'transparent',
            cursor: this.dir === 'horizontal' ? 'col-resize' : 'row-resize',
        });

        document.body.appendChild(mask);
        this.maskElement = mask;
    }
    _removeMask() {
        if (this.maskElement) {
            this.maskElement.remove();
            this.maskElement = null;
        }
    }

    destroy() {
        console.log('>> LayoutResizer destroyed.');
        this.trigger.removeEventListener('mousedown', this._handleMouseDown);
        window.removeEventListener('mousemove', this._handleMouseMove);
        window.removeEventListener('mouseup', this._handleMouseUp);
    }
}

export { LayoutResizer };
