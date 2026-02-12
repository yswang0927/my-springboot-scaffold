import { useState, useRef, useEffect } from "react"

import Resumable from '@/services/resumable';

let base_url = window.APP_BASE_URL ? window.APP_BASE_URL : `${window.location.protocol}//${window.location.hostname}:9090`;
if (base_url.endsWith('/')) {
    base_url = base_url.slice(0, -1);
}

export default function ResumableUploadPage() {
    const divRef = useRef(null);
    const resumableRef = useRef(null);
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
                testChunks: false,
                revertUrl: `${base_url}/api/resumable-upload-revert`, // 撤销已上传的文件接口URL
                autoUpload: false,
                chunkSize: 5 * 1024 * 1024,
                forceChunkSize: true,
                simultaneousUploads: 5,
                directoryUpload: false,
                maxFileSize: 5 * 1024 * 1024 * 1024, // 5GB
            });
            resum.on('fileSuccess', (file) => {
                setUploadedFiles(prev => [...prev, file.fileName]);
            });
            resum.on('fileReverted', (file) => {
                setUploadedFiles(prev => prev.filter(f => f !== file.fileName));
            });
            resum.initUI(divRef.current);
        };

        // 使用 requestAnimationFrame 确保在下一次重绘前执行，此时 DOM 最为稳定
        const rafId = requestAnimationFrame(init);

        return () => {
            console.log(">>> ResumableUploadPage unmounted");
            cancelAnimationFrame(rafId);
            if (resumableRef.current) {
                resumableRef.current.destroy();
                resumableRef.current = null;
            }
        };
    }, []);

    const startUpload = () => {
        if (resumableRef.current) {
            resumableRef.current.upload();
        }
    };

    return (
        <div style={{width:'400px', margin:'0 auto', paddingTop:'50px'}}>
            <div>使用 Resumable.js 组件上传文件</div>
            <div ref={divRef}></div>
            <div style={{padding: '10px 5px', textAlign:'right'}}><button onClick={startUpload}>全部上传</button></div>
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