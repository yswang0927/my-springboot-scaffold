package com.myweb;

import com.myweb.common.LocalFileUploader;
import io.github.eternalstone.captcha.listener.EasyCaptchaListener;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
public class TestController {

    @Value("${spring.servlet.multipart.location}")
    private String uploadDir = System.getProperty("user.dir");

    //注入一个EasyCaptchaListener用于校验验证码
    @Resource
    private EasyCaptchaListener easyCaptchaListener;

    @PostMapping(value = "/api/msgpack-data", produces = "application/x-msgpack")
    public List<Object> testMsgPackData(@RequestBody(required = false) TestBean payload) {
        return List.of("hello", "world", new Date(), LocalDateTime.now(), payload);
    }

    @RequestMapping(value = "/api/json-data")
    public List<TestBean> testData(@RequestBody(required = false) TestBean payload) {
        return List.of(payload != null ? payload : new TestBean());
    }

    @CrossOrigin(originPatterns = "*")
    @PostMapping(value = "/api/fileupload")
    public LocalFileUploader.UploadStatus fileUpload(@RequestPart("file") MultipartFile file, HttpServletResponse response) {
        return LocalFileUploader.upload(new File(uploadDir, file.getOriginalFilename()), file, file.getSize(), 1, 10 * 1024 * 1024, 1);
    }

    @RequestMapping("/api/verify-captcha")
    public boolean verifyCaptcha(HttpServletRequest request, @RequestParam("captcha") String captcha) {
        System.out.println(">>> 收到提交的验证码："+ captcha);
        return easyCaptchaListener.verify(request, captcha);
    }

    /**
     * 流式文件上传。
     * 在 application.properties 文件中将文件大小阈值设置为 0 ，可确保上传的文件直接从请求中流式传输，而不是缓冲在内存中：
     * <pre>
     * spring.servlet.multipart.max-file-size=10MB
     * spring.servlet.multipart.max-request-size=10MB
     * spring.servlet.multipart.file-size-threshold=0
     * </pre>
     * 设置 `spring.servlet.multipart.file-size-threshold=0` 会禁用所有文件的内存缓冲。
     * 任何上传的文件，无论大小，都会直接写入磁盘或作为流进行处理，而不会保存在内存中。
     * 此设置对于处理大文件时可预测的内存使用至关重要，因为它能防止堆内存使用量突然飙升，并允许应用程序在接收到数据后立即开始处理。
     *
     * 由于文件数据是从 MultipartFile 中以流的形式读取的，因此这种方法避免了将整个上传数据缓冲到内存中。
     * transferTo () 方法以内存占用低的方式高效地将输入流复制到输出流。
     * 这使得控制器能够增量式地处理大型文件，从而保持内存使用量的可预测性。
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadFileStreaming(@RequestPart("filePart") MultipartFile filePart) throws IOException {
        Path targetPath = Paths.get(uploadDir).resolve(filePart.getOriginalFilename());
        Files.createDirectories(targetPath.getParent());
        try (InputStream inputStream = filePart.getInputStream();
             OutputStream outputStream = Files.newOutputStream(targetPath)) {
            inputStream.transferTo(outputStream);
        }
        return ResponseEntity.ok("Upload successful: " + filePart.getOriginalFilename());
    }

    /**
     * StreamingResponseBody API 通过直接写入响应输出流来解决这个问题，允许在后续文件仍在处理的同时发送第一个文件。
     * 对于单个 HTTP 响应中的多个文件，我们可以使用 multipart/mixed 内容类型，并用边界字符串分隔流中的每个文件：
     */
    @GetMapping("/download")
    public StreamingResponseBody downloadFiles(HttpServletResponse response) throws IOException {
        String boundary = "filesBoundary";
        response.setContentType("multipart/mixed; boundary=" + boundary);
        List<Path> files = List.of(Paths.get(uploadDir).resolve("file1.txt"), Paths.get(uploadDir).resolve("file2.txt"));
        return outputStream -> {
            try (BufferedOutputStream bos = new BufferedOutputStream(outputStream); OutputStreamWriter writer = new OutputStreamWriter(bos)) {
                for (Path file : files) {
                    writer.write("--" + boundary + "\r\n");
                    writer.write("Content-Type: application/octet-stream\r\n");
                    writer.write("Content-Disposition: attachment; filename=\"" + file.getFileName() + "\"\r\n\r\n");
                    writer.flush();
                    Files.copy(file, bos);
                    bos.write("\r\n".getBytes());
                    bos.flush();
                }
                writer.write("--" + boundary + "--\r\n");
                writer.flush();
            }
        };
    }


    public static class TestBean {
        private String name;
        private int index;
        private boolean flag;
        private Date date;
        private LocalDateTime localDateTime;
        private LocalDate localDate;
        private Instant instant;
        private long longValue;

        public TestBean() {
            this.name = "SpringBoot";
            this.index = 111;
            this.flag = true;
            this.date = new Date();
            this.localDateTime = LocalDateTime.now();
            this.localDate = LocalDate.now();
            this.instant = Instant.now();
            this.longValue = Long.MAX_VALUE;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public boolean isFlag() {
            return flag;
        }

        public void setFlag(boolean flag) {
            this.flag = flag;
        }

        public Date getDate() {
            return date;
        }

        public void setDate(Date date) {
            this.date = date;
        }

        public LocalDateTime getLocalDateTime() {
            return localDateTime;
        }

        public void setLocalDateTime(LocalDateTime localDateTime) {
            this.localDateTime = localDateTime;
        }

        public LocalDate getLocalDate() {
            return localDate;
        }

        public void setLocalDate(LocalDate localDate) {
            this.localDate = localDate;
        }

        public Instant getInstant() {
            return instant;
        }

        public void setInstant(Instant instant) {
            this.instant = instant;
        }

        public long getLongValue() {
            return longValue;
        }

        public void setLongValue(long longValue) {
            this.longValue = longValue;
        }

        @Override
        public String toString() {
            return "TestBean{" +
                    "name='" + name + '\'' +
                    ", index=" + index +
                    ", flag=" + flag +
                    ", date=" + date +
                    ", localDateTime=" + localDateTime +
                    '}';
        }
    }
}
