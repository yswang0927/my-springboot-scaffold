import { useEffect, useState } from "react";

import usePageTitle from "@/hooks/usePageTitle.js";
import request from "@/services/request";

export default function MsgpackPage() {
    usePageTitle("MsgPack测试");

    const [msgpackData, setMsgpackData] = useState([]);

    useEffect(() => {
        /*request.post("/msgpack-data", {
            name: "msgpack",
            date: new Date()
        }, {
            headers: {
                'Content-Type': 'application/x-msgpack',
            },
            responseType: 'arraybuffer'
        }).then(res => {
            console.log(res.data);
            setMsgpackData(res.data.data);
        });*/

        request.post("/json-data", {
            name: "json",
            index: 2026,
            flag: false,
            date: new Date().toLocaleDateString(),
            localDateTime: new Date().toISOString(),
            localDate: new Date().toISOString(),
            instant: new Date().toISOString()
        }).then(res => {
            console.log(res.data);
        });

    }, []);

    return (
        <div>
            <h1>Msgpack测试</h1>
            <ul>{msgpackData.map((item, i) => <li key={item + i}>{item}</li>)}</ul>
        </div>
    );
}