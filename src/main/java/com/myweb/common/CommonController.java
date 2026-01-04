package com.myweb.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CommonController {

    @GetMapping(value = {"", "/", "/index"})
    public ModelAndView index() {
        return new ModelAndView("index");
    }

}
