import axios from "axios";
import { unpack, pack, addExtension } from 'msgpackr';

addExtension({
  Class: Date,
  type: 98,
  write: (date) => date.toLocaleString(),
  read: (dateStr) => new Date(dateStr)
});

function isMsgpackResponse(headers) {
    const contentType = headers['content-type'] || headers['Content-Type'];
    return contentType?.includes('application/x-msgpack') || contentType?.includes('application/msgpack');
}

const request = axios.create({
    baseURL: `${window.location.protocol}//${window.location.hostname}:9090/api`,
    timeout: 10000,
});

// 请求拦截器：自动编码 msgpack
request.interceptors.request.use(
    (config) => {
        // 仅处理 POST 请求 + Content-Type 为 application/x-msgpack 的场景
        if (
            config.method?.toUpperCase() === 'POST' &&
            config.headers['Content-Type']?.includes('application/x-msgpack') &&
            // 仅当请求体是普通对象/数组（非二进制）时才编码
            config.data !== null &&
            typeof config.data === 'object' &&
            !(config.data instanceof Uint8Array) &&
            !(config.data instanceof ArrayBuffer)
        ) {
            // 自动将 JSON 对象编码为 msgpack 二进制（Uint8Array）
            config.data = pack(config.data);
        }
        return config;
    },
    (error) => Promise.reject(error)
);

// 响应拦截器：自动解析 MessagePack 响应
request.interceptors.response.use(
    (response) => {
        if (isMsgpackResponse(response.headers)) {
            response.data = unpack(response.data);
        } else {
            /*try {
                // ArrayBuffer 转字符串 → 解析为 JSON
                response.data = JSON.parse(new TextDecoder().decode(response.data));
            } catch (e) {
                // 非 JSON 文本（如纯文本）直接转字符串
                response.data = new TextDecoder().decode(response.data);
            }*/
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
