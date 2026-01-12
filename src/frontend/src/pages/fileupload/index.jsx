import { useState } from "react"

import { FilePond, registerPlugin } from "react-filepond";
import FilePondPluginFileValidateType from "filepond-plugin-file-validate-type";
import FilePondPluginFileValidateSize from 'filepond-plugin-file-validate-size';

import zh_cn from "filepond/locale/zh-cn.js";
import "filepond/dist/filepond.min.css";

registerPlugin(FilePondPluginFileValidateType, FilePondPluginFileValidateSize);

export default function FileUploadPage() {
    const [files, setFiles] = useState([]);

    const CHUNK_SIZE = 1024 * 1024; // 1MB
    const server = {
        url: 'http://127.0.0.1:9090',
        process: {
            url: '/api/filepond-upload',
            method: 'POST',
            withCredentials: false,
            headers: {},
            timeout: 7000,
            onload: (resp) => {
                console.log('>>>> 上传第一阶段响应:', resp);
                return resp.data;
            },
            onerror: (resp) => {
                console.error('上传出错:', resp);
            },
            ondata: (formData) => {
                // 可以在这里添加额外的数据到 formData
                return formData;
            }
        },
        fetch: null,
        revert: null,
        
    };

    return (
        <div style={{width:'400px', margin:'0 auto', paddingTop:'50px'}}>
            <div>使用 Filepond 组件上传文件</div>
            <FilePond
                files={files}
                name="file"
                onupdatefiles={setFiles}
                allowMultiple={true}
                instantUpload={false}
                allowFileSizeValidation={true}
                maxFiles={3}
                maxFileSize={'100MB'}
                maxTotalFileSize={'300MB'}
                maxParallelUploads={2}
                server={server}
                allowFileTypeValidation={true}
                //acceptedFileTypes={['image/*', 'application/pdf']}
                chunkUploads={true}
                chunkForce={true}
                chunkSize={CHUNK_SIZE}
                credits={['', '']}
                {...zh_cn}
            />
        </div>
    )
}