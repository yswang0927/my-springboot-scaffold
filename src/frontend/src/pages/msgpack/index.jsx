import { useEffect, useState } from "react";

import usePageTitle from "@/hooks/usePageTitle.js";
import request from "@/services/request";

export default function MsgpackPage() {
    usePageTitle("MsgPack测试");

    const [msgpackData, setMsgpackData] = useState([]);

    useEffect(() => {
        request.get("/msgpack-data", {
            headers: {
                'Content-Type': 'application/x-msgpack', // 必须和后端一致
            },
            responseType: 'arraybuffer', // 接收二进制响应
        }).then(res => {
            console.log(res.data);
        });
    }, []);

    return (
        <h1>Msgpack测试</h1>
    );
}