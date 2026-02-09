package com.myweb.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CommonController {

    /**
     * 用于前端路由SPA页面访问适配。
     * 使用否定前瞻来排除 API 和静态资源的路径。
     */
    @GetMapping(path={ "/", "/index", "/{firstPath:^(?!api|static|assets).+}/**" })
    public ModelAndView index(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView("index");
        // 向页面中添加变量
        mv.addObject("contextPath", request.getContextPath());
        return mv;
    }

    @GetMapping("/common/browser-unsupported")
    public ModelAndView browserUnsupported() {
        return new ModelAndView("browser-unsupported");
    }

    @GetMapping("/common/error-404")
    public ModelAndView error404() {
        ModelAndView mv = new ModelAndView("error");
        mv.addObject("errorCode", 404);
        mv.addObject("message", "您访问的页面不存在，请确认访问地址是否正确。");
        return mv;
    }

    @GetMapping(value={"/common/error-500", "/common/error"})
    public ModelAndView error500() {
        ModelAndView mv = new ModelAndView("error");
        mv.addObject("errorCode", 500);
        mv.addObject("message", "噢！发生了点问题。");
        return mv;
    }

}
