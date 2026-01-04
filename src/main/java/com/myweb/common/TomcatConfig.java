package com.myweb.common;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.io.IOException;

@Configuration
public class TomcatConfig {

    @Value("${server.servlet.context-path:}")
    private String contextPath = "";

    @Bean
    public ServletWebServerFactory servletWebServerFactory() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory() {
            @Override
            protected void customizeConnector(Connector connector) {
                connector.setEnableLookups(false);
                connector.setURIEncoding("UTF-8");
                super.customizeConnector(connector);
            }

            @Override
            protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
                tomcat.setAddDefaultWebXmlToWebapp(true);

                // 当 `server.servlet.context-path=/xxx` 配置了具体上下文后，
                // 添加一个根路径映射，用于处理访问 http://ip:port/ 时的请求，避免出现404错误
                if (contextPath != null && contextPath.trim().length() > 1) {
                    Context newContext = tomcat.addContext("", null);
                    tomcat.addServlet(newContext, "rootServlet", new RootServlet(contextPath));
                    newContext.addServletMappingDecoded("/", "rootServlet");
                }

                return super.getTomcatWebServer(tomcat);
            }

        };

        return factory;
    }

    static class RootServlet extends HttpServlet {
        private final String contextPath;

        RootServlet(String contextPath) {
            this.contextPath = contextPath;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            // 忽略favicon.ico请求
            if (req.getRequestURI().endsWith("/favicon.ico")) {
                resp.setStatus(HttpStatus.NO_CONTENT.value());
                return;
            }

            // 当访问 http://ip:port/ 时，重定向到 http://ip:port/contextPath/
            resp.sendRedirect(this.contextPath);
            // 或者 也可以显示一个页面
            /*
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write("<html><head><meta charset=\"UTF-8\"><title>Welcome</title></head>" +
                    "<body><h1>Welcome</h1></body></html>");
            */
        }
    }

}
