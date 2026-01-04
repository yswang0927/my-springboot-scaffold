import { Outlet } from "react-router-dom";

export default function DefaultLayout() {
    return (
        <div style={{ display: "flex", height: "100vh" }}>
            <div style={{width: "200px"}}>
                <ul>
                    <li><a href="/">首页</a></li>
                    <li><a href="/settings">设置</a></li>
                    <li><a href="/msgpack">Msgpack测试</a></li>
                </ul>
            </div>
            <div><Outlet /></div>
        </div>
    );
}
