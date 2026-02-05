import { Outlet, Link } from "react-router-dom";
import { Braces, House, ScanFace, Upload, UploadCloud, Settings } from 'lucide-react';

export default function DefaultLayout() {
    return (
        <div style={{ display: "flex", height: "100vh" }}>
            <div style={{width: "300px", padding: "20px"}}>
                <ul className="flex flex-col gap-6">
                    <li><Link to="/login" className="flex items-center gap-4"> <ScanFace/> 登录</Link></li>
                    <li><Link to="/" className="flex items-center gap-4"> <House/> 首页</Link></li>
                    <li><Link to="/settings" className="flex items-center gap-4"> <Settings/> 设置</Link></li>
                    <li><Link to="/msgpack" className="flex items-center gap-4"> <Braces/> Msgpack测试</Link></li>
                    <li><Link to="/fileupload" className="flex items-center gap-4"> <UploadCloud/> Filepond文件上传</Link></li>
                    <li><Link to="/resumableupload" className="flex items-center gap-4"> <Upload/> Resumable文件上传</Link></li>
                </ul>
            </div>
            <div style={{flex: 1}}><Outlet /></div>
        </div>
    );
}
