package com.myweb;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
public class TestController {

    @PostMapping(value = "/api/msgpack-data", produces = "application/x-msgpack")
    public List<Object> testMsgPackData(@RequestBody(required = false) TestBean payload) {
        return List.of("hello", "world", new Date(), LocalDateTime.now(), payload);
    }

    @RequestMapping(value = "/api/json-data")
    public List<TestBean> testData(@RequestBody(required = false) TestBean payload) {
        return List.of(payload != null ? payload : new TestBean());
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
