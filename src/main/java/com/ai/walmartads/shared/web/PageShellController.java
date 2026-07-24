package com.ai.walmartads.shared.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class PageShellController {

    private static final String STYLE_TAG = "<link rel=\"stylesheet\" href=\"/returns.css\">";
    private static final String SCRIPT_TAG = "<script src=\"/returns.js\" defer></script>";
    private volatile String page;

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public String page() throws IOException {
        String cached = page;
        if (cached != null) {
            return cached;
        }
        String source = new ClassPathResource("static/index.html")
                .getContentAsString(StandardCharsets.UTF_8);
        cached = source
                .replace("</head>", STYLE_TAG + "</head>")
                .replace("</body>", SCRIPT_TAG + "</body>");
        page = cached;
        return cached;
    }
}
