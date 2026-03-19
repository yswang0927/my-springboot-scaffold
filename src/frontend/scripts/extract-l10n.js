import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

// --- 获取当前文件的绝对路径 (替代 __dirname) ---
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// --- 配置区域 ---
const SRC_DIR = path.resolve(__dirname, '../src');
const LOCALES_DIR = path.resolve(__dirname, '../src/locales');
const TARGET_LANGUAGES = ['zh-CN', 'en', 'jp'];
const EXTENSIONS = ['.js', '.jsx', '.ts', '.tsx'];
const EXTRACT_REGEX = /\bt\(\s*(['"`])(.*?)\1/g;

// 1. 递归获取目录下所有匹配的文件
function getAllFiles(dirPath, arrayOfFiles = []) {
    const files = fs.readdirSync(dirPath);

    files.forEach(function (file) {
        const fullPath = path.join(dirPath, file);
        if (fs.statSync(fullPath).isDirectory()) {
            if (!fullPath.includes('locales')) {
                getAllFiles(fullPath, arrayOfFiles);
            }
        } else {
            if (EXTENSIONS.includes(path.extname(fullPath))) {
                arrayOfFiles.push(fullPath);
            }
        }
    });

    return arrayOfFiles;
}

// 2. 解析已有的 locale.js 文件内容
function parseExistingLocale(filePath) {
    if (!fs.existsSync(filePath)) return {};

    const content = fs.readFileSync(filePath, 'utf-8');
    const match = content.match(/export\s+default\s+(\{[\s\S]*\});?/);

    if (match && match[1]) {
        try {
            // 使用 Function 解析，避免 JSON.parse 对格式的严苛要求
            return new Function(`return ${match[1]}`)();
        } catch (e) {
            console.error(`❌ 解析已有语言文件失败: ${filePath}`, e);
            return {};
        }
    }
    return {};
}

// --- 主流程 ---
function run() {
    console.log('🔍 开始扫描源码提取 l10n 关键字...');
    const files = getAllFiles(SRC_DIR);
    const extractedKeys = new Set();

    files.forEach(file => {
        const content = fs.readFileSync(file, 'utf-8');
        let match;
        while ((match = EXTRACT_REGEX.exec(content)) !== null) {
            if (match[2]) {
                extractedKeys.add(match[2]);
            }
        }
    });

    console.log(`✅ 共提取到 ${extractedKeys.size} 个唯一的关键字。`);

    if (!fs.existsSync(LOCALES_DIR)) {
        fs.mkdirSync(LOCALES_DIR, { recursive: true });
    }

    TARGET_LANGUAGES.forEach(lang => {
        const filePath = path.join(LOCALES_DIR, `${lang}.js`);
        const existingData = parseExistingLocale(filePath);
        let addedCount = 0;

        extractedKeys.forEach(key => {
            if (!(key in existingData)) {
                existingData[key] = key;
                addedCount++;
            }
        });

        const sortedKeys = Object.keys(existingData).sort();
        const newData = {};
        sortedKeys.forEach(k => {
            newData[k] = existingData[k];
        });

        const fileContent = `// Auto-generated l10n file\nexport default ${JSON.stringify(newData, null, 2)};\n`;

        fs.writeFileSync(filePath, fileContent, 'utf-8');
        console.log(`📝 更新 ${lang}.js : 新增了 ${addedCount} 个词条。`);
    });

    console.log('🎉 提取完成！');
}

run();