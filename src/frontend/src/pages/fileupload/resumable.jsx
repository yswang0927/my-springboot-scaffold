import { useState, useRef, useEffect } from "react"

import Resumable from '@/services/resumable';

let base_url = window.APP_BASE_URL ? window.APP_BASE_URL : `${window.location.protocol}//${window.location.hostname}:9090`;
if (base_url.endsWith('/')) {
    base_url = base_url.slice(0, -1);
}

export default function ResumableUploadPage() {
    const divRef = useRef(null);
    const dirRef = useRef(null);
    const resumableRef = useRef(null);
    const resumableRef2 = useRef(null);
    const [uploadedFiles, setUploadedFiles] = useState([]);

    useEffect(() => {
        if (resumableRef.current || !divRef.current) {
            return;
        }

        const init = () => {
            if (resumableRef.current || !divRef.current) {
                return;
            }

            const resum = resumableRef.current = new Resumable({
                url: `${base_url}/api/resumable-upload`,
                testUrl: `${base_url}/api/resumable-upload-test`,
                testChunks: true,
                revertUrl: `${base_url}/api/resumable-upload-revert`, // 撤销已上传的文件接口URL
                autoUpload: false,
                chunkSize: 5 * 1024 * 1024,
                forceChunkSize: true,
                simultaneousUploads: 3,
                directoryUpload: false,
                resumableUpload: true, // 是否开启断点续传，它需要服务器端配合支持，开启后，前端会进行文件的MD5计算，对于大文件MD5计算过程会比较慢
                maxFileSize: 5 * 1024 * 1024 * 1024, // 5GB
            });
            resum.on('fileSuccess', (file) => {
                console.log(file);
                setUploadedFiles(prev => [...prev, file.fileName]);
            });
            resum.on('fileReverted', (file) => {
                setUploadedFiles(prev => prev.filter(f => f !== file.fileName));
            });
            resum.initUI(divRef.current);

            // 上传文件夹
            const resum2 = resumableRef2.current = new Resumable({
                url: `${base_url}/api/resumable-upload`,
                testUrl: `${base_url}/api/resumable-upload-test`,
                testChunks: true,
                revertUrl: `${base_url}/api/resumable-upload-revert`, // 撤销已上传的文件接口URL
                autoUpload: false,
                chunkSize: 5 * 1024 * 1024,
                forceChunkSize: true,
                simultaneousUploads: 3,
                directoryUpload: true, // 上传文件夹
                resumableUpload: true, // 是否开启断点续传，它需要服务器端配合支持，开启后，前端会进行文件的MD5计算，对于大文件MD5计算过程会比较慢
                maxFileSize: 5 * 1024 * 1024 * 1024, // 5GB
            });
            resum2.on('fileSuccess', (file) => {
                console.log(file);
                setUploadedFiles(prev => [...prev, file.relativePath || file.fileName]);
            });
            resum2.on('fileReverted', (file) => {
                setUploadedFiles(prev => prev.filter(f => (f !== file.relativePath && f !== file.fileName)));
            });
            resum2.initUI(dirRef.current);
        };

        // 使用 requestAnimationFrame 确保在下一次重绘前执行，此时 DOM 最为稳定
        const rafId = requestAnimationFrame(init);

        return () => {
            console.log(">>> ResumableUploadPage unmounted");
            cancelAnimationFrame(rafId);
            [resumableRef, resumableRef2].forEach((ref) => {
                if (ref.current) {
                    ref.current.destroy();
                    ref.current = null;
                }
            });
        };
    }, []);

    const startUpload = () => {
        if (resumableRef.current) {
            resumableRef.current.upload();
        }
    };

    const startUpload2 = () => {
        if (resumableRef2.current) {
            resumableRef2.current.upload();
        }
    };

    return (
        <div style={{width:'400px', margin:'0 auto', paddingTop:'50px'}}>
            <div>使用 Resumable.js 组件上传文件</div>
            <div ref={divRef}></div>
            <div style={{padding: '10px 5px', textAlign:'right'}}><button onClick={startUpload}>全部上传</button></div>

            <div>使用 Resumable.js 组件上传文件夹</div>
            <div ref={dirRef}></div>
            <div style={{padding: '10px 5px', textAlign:'right'}}><button onClick={startUpload2}>全部上传</button></div>

            <div>
                <h3>已上传的文件：</h3>
                <ul>
                    {uploadedFiles.map((file, index) => (
                        <li key={index}><a href={`${base_url}/api/stream-download?filename=${file}`} target="_blank">{file}</a></li>
                    ))}
                </ul>
            </div>
        </div>
    )
}