import { Outlet, Link } from "react-router-dom";

export default function DefaultLayout() {
    return (
        <div style={{ display: "flex", height: "100vh" }}>
            <div style={{width: "200px"}}>
                <ul>
                    <li><Link to="/login">登录</Link></li>
                    <li><Link to="/">首页</Link></li>
                    <li><Link to="/settings">设置</Link></li>
                    <li><Link to="/msgpack">Msgpack测试</Link></li>
                </ul>
            </div>
            <div style={{flex: 1}}><Outlet /></div>
        </div>
    );
}
