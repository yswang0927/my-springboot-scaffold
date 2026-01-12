import { Routes, Route } from "react-router-dom";
import DefaultLayout from "./layouts/DefaultLayout";
import Home from "./pages/home";
import Login from "./pages/login";
import Settings from "./pages/setting";
import MsgpackPage  from "./pages/msgpack";
import FileUploadPage from "./pages/fileupload";

export default function Router() {
    return (
        <Routes>
            <Route element={<DefaultLayout />}>
                <Route path="/" element={<Home />} />
                <Route path="/login" element={<Login />} />
                <Route path="/settings" element={<Settings />} />
                <Route path="/msgpack" element={<MsgpackPage />} />
                <Route path="/fileupload" element={<FileUploadPage />} />
            </Route>
        </Routes>
    );
}
