import { Outlet, Link } from "react-router-dom";
import { Bot, Braces, House, ScanFace, Upload, UploadCloud, Settings } from 'lucide-react';

import { useL10n } from "@/l10n";

export default function DefaultLayout() {
    const { t, lang, setLang } = useL10n();

    return (
        <div style={{ display: "flex", height: "100vh" }}>
            <div style={{width: "300px", padding: "20px"}}>
                <ul className="flex flex-col gap-6">
                    <li>语言:
                        <select value={lang} onChange={(e) => setLang(e.target.value)} style={{width:"100px"}}>
                            <option value="en">{t("英语")}</option>
                            <option value="zh-CN">{t("中文")}</option>
                        </select>
                    </li>
                    <li><Link to="/login" className="flex items-center gap-4"> <ScanFace/> {t("登录")}</Link></li>
                    <li><Link to="/" className="flex items-center gap-4"> <House/> {t("首页")}</Link></li>
                    <li><Link to="/settings" className="flex items-center gap-4"> <Settings/> {t("设置")}</Link></li>
                    <li><Link to="/msgpack" className="flex items-center gap-4"> <Braces/> {t("Msgpack测试")}</Link></li>
                    <li><Link to="/fileupload" className="flex items-center gap-4"> <UploadCloud/> {t("Filepond文件上传")}</Link></li>
                    <li><Link to="/resumableupload" className="flex items-center gap-4"> <Upload/> {t("Resumable文件上传")}</Link></li>
                    <li><Link to="/aichat" className="flex items-center gap-4"> <Bot/> {t("AI聊天")}</Link></li>
                    <li><Link to="/markdown" className="flex items-center gap-4"> <Bot/> {t("Markdown")}</Link></li>
                </ul>
            </div>
            <div style={{flex: 1, height: "100vh"}}><Outlet /></div>
        </div>
    );
}
