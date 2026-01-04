package com.myweb;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TestController {

    @GetMapping(value = "/api/msgpack-data", produces = "application/x-msgpack")
    public List<String> testMsgPackData() {
        return List.of("hello", "world");
    }

}
