import { useState, useRef, useEffect } from "react"

import Resumable from '@/services/resumable';

let base_url = window.APP_BASE_URL ? window.APP_BASE_URL : `${window.location.protocol}//${window.location.hostname}:9090`;
if (base_url.endsWith('/')) {
    base_url = base_url.slice(0, -1);
}

export default function ResumableUploadPage() {
    const divRef = useRef(null);
    const resumableRef = useRef(null);

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
                autoUpload: true,
                chunkSize: 5 * 1024 * 1024,
                forceChunkSize: true,
                simultaneousUploads: 3,
                directoryUpload: true,
                maxFileSize: 5 * 1024 * 1024 * 1024, // 5GB
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

    return (
        <div style={{width:'400px', margin:'0 auto', paddingTop:'50px'}}>
            <div>使用 Resumable.js 组件上传文件</div>
            <div ref={divRef}></div>
        </div>
    )
}