package com.myweb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ApplicationStartupListener implements CommandLineRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationStartupListener.class);

    @Override
    public void run(String... args) throws Exception {
        String[] sysProps = { "os.name", "os.arch", "java.home", "java.version", "user.home", "user.dir", "user.timezone" };
        StringBuilder sysPropsInfo = new StringBuilder(512);
        sysPropsInfo.append("\n================= Java System Properties =================");
        for (String pkey : sysProps) {
            sysPropsInfo.append('\n').append(pkey).append(": ").append(System.getProperty(pkey));
        }
        sysPropsInfo.append("\n==========================================================");
        LOG.info(sysPropsInfo.toString());


    }

}
