import axios from "axios";
import { unpack, pack } from 'msgpackr';

/**
 * 判断响应头是否为 MessagePack 格式
 * @param {Object} headers Axios 响应头对象
 * @returns {boolean}
 */
function isMsgpackResponse(headers) {
    const contentType = headers['content-type'] || headers['Content-Type'];
    return contentType?.includes('application/x-msgpack') || contentType?.includes('application/msgpack');
}

const request = axios.create({
    baseURL: `${window.location.protocol}//${window.location.hostname}:9090/api`,
    timeout: 10000,
});

// 响应拦截器：自动解析 MessagePack 响应
request.interceptors.response.use(
    (response) => {
        // 1. 判断响应是否为 MessagePack 格式
        if (isMsgpackResponse(response.headers)) {
            // 2. 用 msgpackr 解析二进制数据
            response.data = unpack(response.data);
        } else {
            // 3. 非 MessagePack 响应：还原为 JSON/文本（兼容普通响应）
            try {
                // ArrayBuffer 转字符串 → 解析为 JSON
                response.data = JSON.parse(new TextDecoder().decode(response.data));
            } catch (e) {
                // 非 JSON 文本（如纯文本）直接转字符串
                response.data = new TextDecoder().decode(response.data);
            }
        }
        return response;
    },
    (error) => {
        // 错误响应也可按同样逻辑处理（可选）
        if (error.response && isMsgpackResponse(error.response.headers)) {
            error.response.data = unpack(error.response.data);
        }
        return Promise.reject(error);
    }
);

export default request;
