package com.myweb.util;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public class OSUtils {

    /**
     * 检测一个端口号是否可用
     * @param port 端口号
     * @return true - 可用, false - 不可用
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(false);  // 明确禁用，确保真实占用检测
            ss.bind(new InetSocketAddress("0.0.0.0", port));
        } catch (Exception e) {
            return false;
        }

        try (DatagramSocket ds = new DatagramSocket(null)) {
            ds.setReuseAddress(false);
            ds.bind(new InetSocketAddress("0.0.0.0", port));
        } catch (Exception e) {
            return false;
        }

        return true;
    }

}
