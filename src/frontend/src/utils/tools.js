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
 *  key: "resizer1", // 如果配置了,则可以自动记忆
 *  trigger: document.querySelector('.layout1 .layout-resizer'),
 *  target: document.querySelector('.layout1 .layout-resizer').parentElement
 * });
 *
 * // 或者通过 onResizing 自己写resize目标方式
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
export class LayoutResizer {
    constructor(options) {
        const opts = (typeof options === 'object') ? options : {};
        this.trigger = opts.trigger; // [必须]定义resizer手柄是哪个DOM元素
        this.target = opts.target;   // [可选]定义resize目标DOM元素, 如果未定义, 则需要自己在 `onResizing()` 函数中写resize逻辑
        this.onResizeStart = opts.onResizeStart || (() => {});
        this.onResizing = opts.onResizing || (() => {});
        this.onResizeEnd = opts.onResizeEnd || (() => {});

        if (this.trigger && typeof this.trigger === 'string') {
            this.trigger = document.querySelector(this.trigger);
        }

        if (!this.trigger) {
            console.warn('LayoutResizer: 未找到触发拖拽的 DOM 元素。');
            return;
        }

        if (this.target && typeof this.target === 'string') {
            this.target = document.querySelector(this.target);
        }

        // 获取区域，默认为左侧
        this._region = this.trigger.getAttribute('data-region') || 'left';
        this._dir = ['top', 'bottom'].includes(this._region) ? 'vertical' : 'horizontal';

        // 范围限制
        let minVal = Number(opts.hasOwnProperty('min') ? opts.min : this.trigger.getAttribute('data-min'));
        let maxVal = Number(opts.hasOwnProperty('max') ? opts.max : this.trigger.getAttribute('data-max'));
        this.min = isNaN(minVal) ? 0 : minVal;
        this.max = isNaN(maxVal) ? 99999 : maxVal;

        // 用于自动记忆上一次resize的大小
        this._key = opts.key ? "layout_resizer_"+ opts.key : null;

        // 绑定上下文
        this._handleMouseDown = this._handleMouseDown.bind(this);
        this._handleMouseMove = this._handleMouseMove.bind(this);
        this._handleMouseUp = this._handleMouseUp.bind(this);

        this._currentSize = 0;
        this._maskElement = null;

        this.init();
    }

    init() {
        requestAnimationFrame(() => {
            this.trigger.addEventListener('mousedown', this._handleMouseDown);

            // 从记忆恢复
            if (this._key) {
                var savedSize = window.localStorage.getItem(this._key);
                if (savedSize !== null) {
                    savedSize = parseInt(savedSize);
                    if (isNaN(savedSize)) {
                        window.localStorage.removeItem(this._key);
                        return;
                    }
                    this._resizeTarget(savedSize);
                }
            }
        });
    }

    _handleMouseDown(e) {
        e.preventDefault();
        this._startX = e.clientX;
        this._startY = e.clientY;

        const rect = (this.target || this.trigger.parentElement).getBoundingClientRect();
        this._startWidth = rect.width;
        this._startHeight = rect.height;

        this._currentSize = (this._dir === 'horizontal') ? this._startWidth : this._startHeight;
        this._createMask();
        this.onResizeStart(e);
        this.trigger.classList.add('dragging');
        document.body.style.userSelect = 'none';

        window.addEventListener('mousemove', this._handleMouseMove);
        window.addEventListener('mouseup', this._handleMouseUp);
    }

    _handleMouseMove(e) {
        let currentSize;

        if (this._dir === 'horizontal') {
            const deltaX = e.clientX - this._startX;
            // right 减，left 加
            currentSize = this._region === 'right' ? this._startWidth - deltaX : this._startWidth + deltaX;
        } else {
            const deltaY = e.clientY - this._startY;
            // bottom 减，top 加
            currentSize = this._region === 'bottom' ? this._startHeight - deltaY : this._startHeight + deltaY;
        }

        // 边界限制
        currentSize = this._currentSize = Math.max(this.min, Math.min(this.max, currentSize));

        this._resizeTarget(currentSize);

        if (typeof this.onResizing === 'function') {
            this.onResizing(currentSize, this._region, e);
        }
    }

    _handleMouseUp(e) {
        window.removeEventListener('mousemove', this._handleMouseMove);
        window.removeEventListener('mouseup', this._handleMouseUp);
        this._removeMask();
        this.onResizeEnd(this._currentSize, e);
        this.trigger.classList.remove('dragging');
        document.body.style.userSelect = '';
        // 记忆
        if (this._key) {
            window.localStorage.setItem(this._key, this._currentSize);
        }
    }

    _resizeTarget(size) {
        if (this.target) {
            if (this._dir === 'horizontal') {
                this.target.style.width = this.target.style.minWidth = size + 'px';
            } else {
                this.target.style.height = this.target.style.minHeight = size + 'px';
            }
        }
    }

    // 创建全屏透明遮罩
    _createMask() {
        const mask = this._maskElement = document.createElement('div');
        Object.assign(mask.style, {
            position: 'fixed',
            top: 0,
            left: 0,
            width: '100vw',
            height: '100vh',
            zIndex: 999999, // 确保在最上层，挡住 iframe 和其他业务组件
            backgroundColor: 'transparent',
            cursor: this._dir === 'horizontal' ? 'col-resize' : 'row-resize',
        });

        document.body.appendChild(mask);
    }
    _removeMask() {
        if (this._maskElement) {
            this._maskElement.remove();
            this._maskElement = null;
        }
    }

    destroy() {
        if (this.trigger) {
            this.trigger.removeEventListener('mousedown', this._handleMouseDown);
        }
        window.removeEventListener('mousemove', this._handleMouseMove);
        window.removeEventListener('mouseup', this._handleMouseUp);
        console.log(">> LayoutResizer destroyed.");
    }
}
