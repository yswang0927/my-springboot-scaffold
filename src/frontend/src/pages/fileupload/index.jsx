import { useState, useRef, useEffect } from "react"

import * as FilePond from 'filepond';
import FilePondPluginFileValidateType from "filepond-plugin-file-validate-type";
import FilePondPluginFileValidateSize from 'filepond-plugin-file-validate-size';

import zh_cn from "filepond/locale/zh-cn.js";
import "filepond/dist/filepond.min.css";

FilePond.registerPlugin(FilePondPluginFileValidateType, FilePondPluginFileValidateSize);

export default function FileUploadPage() {
    const [files, setFiles] = useState([]);
    const fileInputRef = useRef(null);
    const pondRef = useRef(null);

    const ALLOWED_FILE_TYPE = null; //['image/*', 'application/pdf']
    const MAX_FILE_SIZE = "2000MB";
    const MAX_TOTAL_FILE_SIZE = "20000MB";
    const MAX_FILES = 10;
    const MAX_PARALLEL_UPLOADS = 5;
    const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB
    const CHUNK_FORCE = false;

    const serverOptions = {
        url: 'http://127.0.0.1:9090/api/filepond-upload',
        process: {
            url: '/process',
            method: 'POST',
            withCredentials: false,
            headers: (file) => {
                console.log('>>>> 上传第一阶段请求头:', file);
                return {
                    'Upload-Name': encodeURIComponent(file.name),
                    'Upload-Length': file.size,
                    'Upload-Path': encodeURIComponent(file.webkitRelativePath || '') // 支持上传目录
                };
            },
            timeout: 7000,
            onload: (resp) => {
                // 当收到服务器响应时调用，可用于从服务器响应中获取唯一的文件 ID
                // 当直接上传小文件(非分块)时，resp 通常是服务器返回的文件 ID
                if (typeof resp === 'string') {
                    return resp;
                }
                // 当分块上传时，resp 通常是一个包含响应文本的对象
                if (resp && typeof resp === 'object' && resp.responseText) {
                    return resp.responseText;
                }
                return '';
            },
            onerror: (xhr) => {
                // 当收到服务器错误时调用，接收响应正文，用于选择相关的错误数据。
                console.error('上传出错:', xhr);
            },
            ondata: (formData) => {
                // 在表单数据对象即将发送之前调用此方法，返回扩展后的表单数据对象以进行更改。
                // formData.append('Hello', 'World');
                return formData;
            }
        },
        patch: '/process?patch=',
        revert: '/revert',
        fetch: '/fetch',
        restore: null,
        load: null,
    };

    useEffect(() => {
        if (pondRef.current || !fileInputRef.current) {
            return;
        }

        const initPond = () => {
            if (!fileInputRef.current) {
                return;
            }

            pondRef.current = FilePond.create(fileInputRef.current, {
                name: 'file',
                server: serverOptions,
                allowMultiple: true,
                instantUpload: false, // 是否选择文件后立即上传
                allowFileSizeValidation: true,
                allowFileTypeValidation: ALLOWED_FILE_TYPE !== null,
                allowMinimumUploadDuration: true,
                acceptedFileTypes: ALLOWED_FILE_TYPE,
                maxFiles: MAX_FILES,
                maxFileSize: MAX_FILE_SIZE,
                maxTotalFileSize: MAX_TOTAL_FILE_SIZE,
                maxParallelUploads: MAX_PARALLEL_UPLOADS,
                chunkUploads: true,
                chunkForce: CHUNK_FORCE,
                chunkSize: CHUNK_SIZE,
                credits: ['', ''],
                ...zh_cn
            });
        };

        // 使用 requestAnimationFrame 确保在下一次重绘前执行，此时 DOM 最为稳定
        const rafId = requestAnimationFrame(initPond);

        return () => {
            cancelAnimationFrame(rafId);
            if (pondRef.current) {
                pondRef.current.destroy();
                pondRef.current = null;
            }
        };
    }, []);

    return (
        <div style={{width:'400px', margin:'0 auto', paddingTop:'50px'}}>
            <div>使用 Filepond 组件上传文件</div>
            <div>
                <input type="file" ref={fileInputRef} />
            </div>
        </div>
    )
}