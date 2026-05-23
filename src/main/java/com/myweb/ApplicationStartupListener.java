package com.myweb;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ApplicationStartupListener implements CommandLineRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationStartupListener.class);

    @Value("${server.port:80}")
    private int serverPort = 80;

    @Value("${server.servlet.context-path:}")
    private String contextPath = "";

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled = false;


    @Override
    public void run(String... args) throws Exception {
        String[] sysProps = { "os.name", "os.arch", "java.home", "java.version", "user.home", "user.dir", "user.timezone" };
        StringBuilder sysPropsInfo = new StringBuilder(512);
        sysPropsInfo.append("\n================= Java System Environment =================");
        for (String pkey : sysProps) {
            sysPropsInfo.append('\n').append(pkey).append(": ").append(System.getProperty(pkey));
        }

        List<String> hostIps = getLocalHostIpAddress();
        if (hostIps != null && !hostIps.isEmpty()) {
            sysPropsInfo.append("\nVisit URL:");
            for (String ip : hostIps) {
                sysPropsInfo.append("\n  ").append(sslEnabled ? "https://" : "http://").append(ip);
                if (serverPort != 80 && serverPort != 443) {
                    sysPropsInfo.append(":").append(serverPort);
                }
                if (StringUtils.hasText(contextPath) && !"/".equals(contextPath)) {
                    sysPropsInfo.append(contextPath);
                }
            }
        }
        sysPropsInfo.append("\n==========================================================");
        LOG.info(sysPropsInfo.toString());
    }

    private static List<String> getLocalHostIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<String> ipv4 = new ArrayList<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isLoopback() && !networkInterface.isVirtual() && networkInterface.isUp()) {
                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddr = inetAddresses.nextElement();
                        if (!inetAddr.isLoopbackAddress() && !inetAddr.isLinkLocalAddress() && (inetAddr instanceof Inet4Address)) {
                            String hostAddress = inetAddr.getHostAddress();
                            if (StringUtils.hasText(hostAddress)) {
                                ipv4.add(hostAddress);
                            }
                        }
                    }
                }
            }

            return ipv4;
        } catch (Exception e) {
            LOG.error("Failed to get local-host ip address.", e);
        }

        return Collections.emptyList();
    }

}
