package com.ai.walmartads.operations.application;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
public class OperationWindowCalculator {

    public EvaluationWindow calculate(LocalDate actionDate, LocalDate latestDataDate, int requestedDays) {
        if (actionDate == null) {
            throw new IllegalArgumentException("操作日期不能为空。");
        }
        int safeDays = Math.max(1, Math.min(requestedDays, 90));
        long availableAfter = latestDataDate == null
                ? 0
                : Math.max(0, ChronoUnit.DAYS.between(actionDate, latestDataDate));
        int comparedDays = (int) Math.min(safeDays, availableAfter);
        int baselineDays = comparedDays > 0 ? comparedDays : safeDays;

        LocalDate beforeEnd = actionDate.minusDays(1);
        LocalDate beforeStart = beforeEnd.minusDays(baselineDays - 1L);
        LocalDate afterStart = actionDate.plusDays(1);
        LocalDate afterEnd = comparedDays == 0 ? null : actionDate.plusDays(comparedDays);
        String state = comparedDays == 0
                ? "WAITING"
                : comparedDays < safeDays ? "COLLECTING" : "READY";

        return new EvaluationWindow(
                safeDays,
                comparedDays,
                state,
                beforeStart,
                beforeEnd,
                afterStart,
                afterEnd,
                latestDataDate
        );
    }

    public record EvaluationWindow(
            int requestedDays,
            int comparedDays,
            String state,
            LocalDate beforeStart,
            LocalDate beforeEnd,
            LocalDate afterStart,
            LocalDate afterEnd,
            LocalDate latestDataDate
    ) {
    }
}
