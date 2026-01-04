import { useEffect, useState } from "react";
import usePageTitle from "@/hooks/usePageTitle.js";

export default function Home() {
    usePageTitle("首页");

    return (
        <h1>Home页面</h1>
    );
}