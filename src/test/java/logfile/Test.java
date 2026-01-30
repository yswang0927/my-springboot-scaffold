package logfile;

import java.io.File;
import java.io.IOException;

public class Test {
    public static void main(String[] args) throws IOException {
        File file = new File(System.getProperty("user.dir"), "logs/application.log");
        try(HugeTextFileReader reader = new HugeTextFileReader(file.getAbsolutePath())) {

            long stime = System.currentTimeMillis();
            Pair<String, LongRange> res = reader.readFullLines(560, 236);
            System.out.println("Cost Time: " + (System.currentTimeMillis() - stime));
            System.out.println(res.first);
            System.out.println(res.second);

            // 从后往前读
            long totalLength = reader.lengthInBytes();
            int readLenth = 512;
            Pair<String, LongRange> res2 = reader.readFullLinesBefore(totalLength, readLenth);
            System.out.println(res2.first);
        }
    }
}
