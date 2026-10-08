package com.ai.walmartads.walmartad;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Prevents a transient empty or truncated MCP result from replacing a healthy stored window. */
final class WalmartAdSyncGuard {

    private static final long RATIO_CHECK_MINIMUM = 20;
    private static final double MIN_RETAINED_RATIO = 0.70;

    private WalmartAdSyncGuard() {
    }

    static void validate(
            Map<CoverageKey, Long> existingCoverage,
            Map<CoverageKey, Long> fetchedCoverage
    ) {
        List<String> anomalies = new ArrayList<>();
        existingCoverage.forEach((key, previous) -> {
            long current = fetchedCoverage.getOrDefault(key, 0L);
            boolean becameEmpty = previous > 0 && current == 0;
            boolean droppedSharply = previous >= RATIO_CHECK_MINIMUM
                    && current < Math.ceil(previous * MIN_RETAINED_RATIO);
            if (becameEmpty || droppedSharply) {
                anomalies.add(key + " previous=" + previous + ", fetched=" + current);
            }
        });
        if (!anomalies.isEmpty()) {
            throw new IllegalStateException(
                    "LingXing ad coverage is suspicious; stored data was preserved: "
                            + String.join("; ", anomalies));
        }
    }

    record CoverageKey(
            LocalDate reportDate,
            long advertiserId,
            String adType,
            String dataset
    ) {
    }
}
