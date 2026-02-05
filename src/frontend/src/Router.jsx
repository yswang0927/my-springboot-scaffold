import { Suspense, lazy } from 'react';
import { Routes, Route } from "react-router-dom";
import DefaultLayout from "./layouts/DefaultLayout";

// 懒加载页面组件
const Home = lazy(() => import('./pages/home'));
const Login = lazy(() => import('./pages/login'));
const Settings = lazy(() => import('./pages/setting'));
const MsgpackPage = lazy(() => import('./pages/msgpack'));
const FileUploadPage = lazy(() => import('./pages/fileupload'));
const ResumableUploadPage = lazy(() => import('./pages/fileupload/resumable'));
const AIChat = lazy(() => import('./pages/aichat/Chat'));

export default function Router() {
    return (
        <Suspense fallback={<div>Loading...</div>}>
            <Routes>
                <Route element={<DefaultLayout />}>
                    <Route path="/" element={<Home />} />
                    <Route path="/login" element={<Login />} />
                    <Route path="/settings" element={<Settings />} />
                    <Route path="/msgpack" element={<MsgpackPage />} />
                    <Route path="/fileupload" element={<FileUploadPage />} />
                    <Route path="/resumableupload" element={<ResumableUploadPage />} />
                    <Route path="/aichat" element={<AIChat />} />
                </Route>
            </Routes>
        </Suspense>
    );
}
