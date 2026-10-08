package com.ai.walmartads.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LingXingPropertiesTest {

    @Test
    void adWindowAlwaysCoversAttributionPeriod() {
        assertThat(properties(0).resolvedAdWindowDays()).isEqualTo(16);
        assertThat(properties(8).resolvedAdWindowDays()).isEqualTo(16);
        assertThat(properties(20).resolvedAdWindowDays()).isEqualTo(20);
        assertThat(properties(60).resolvedAdWindowDays()).isEqualTo(31);
    }

    private static LingXingProperties properties(int adWindowDays) {
        // 参数顺序见 LingXingProperties 记录：末尾三个是评价同步（comment*）字段
        return new LingXingProperties(
                false, null, null, null, 0, null, 0, List.<Long>of(), adWindowDays, null,
                null, 0, null, null);
    }
}
