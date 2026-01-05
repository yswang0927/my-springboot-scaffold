import { useEffect, useState } from "react";
import { useRequest } from "ahooks";

import usePageTitle from "@/hooks/usePageTitle.js";
import request from "@/services/request";

export default function MsgpackPage() {
    usePageTitle("MsgPack测试");

    const [msgpackData, setMsgpackData] = useState([]);

    const { data, error, loading } = useRequest(() => request.post("/json-data", {
        name: "json",
        index: 2026,
        flag: false,
        date: new Date().toLocaleDateString(),
        localDateTime: new Date().toISOString(),
        localDate: new Date().toISOString(),
        instant: new Date().toISOString()
    }));
    if (!loading) {
        console.log("useRequest data:", data);
    }

    useEffect(() => {
        request.post("/msgpack-data", {
            name: "json",
            index: 2026,
            flag: false,
            date: new Date(),
            localDateTime: new Date().toISOString(),
            localDate: new Date().toLocaleDateString(),
            instant: new Date().toUTCString()
        }, {
            headers: {
                'Content-Type': 'application/x-msgpack',
            },
            responseType: 'arraybuffer'
        }).then(resData => {
            console.log(resData);
            setMsgpackData(resData.data);
        });

        
        /*request.post("/json-data", {
            name: "json",
            index: 2026,
            flag: false,
            date: new Date().toLocaleDateString(),
            localDateTime: new Date().toISOString(),
            localDate: new Date().toISOString(),
            instant: new Date().toISOString()
        }).then(resData => {
            console.log(resData);
        });*/

        return () => {
            console.log('>> 卸载页面：MsgpackPage');
        };
    }, []);

    return (
        <div>
            <h1>Msgpack测试</h1>
            <pre>{JSON.stringify(msgpackData, null, 2)}</pre>
        </div>
    );
}