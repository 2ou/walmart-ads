package com.ai.walmartads.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PageShellControllerTest {

    @Test
    void workbenchRoutesIncludeReturnsAssetsOnce() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PageShellController()).build();

        for (String path : new String[]{"/", "/index.html"}) {
            String page = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(StringUtils.countOccurrencesOf(page, "returns.js")).as(path).isEqualTo(1);
            assertThat(StringUtils.countOccurrencesOf(page, "returns.css")).as(path).isEqualTo(1);
        }
    }
}
