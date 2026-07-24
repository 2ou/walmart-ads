package com.ai.walmartads.operations.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OperationWindowCalculatorTest {

    private final OperationWindowCalculator calculator = new OperationWindowCalculator();

    @Test
    void usesFullEqualWindowsWhenEnoughAfterDataExists() {
        OperationWindowCalculator.EvaluationWindow window = calculator.calculate(
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 22),
                7
        );

        assertThat(window.state()).isEqualTo("READY");
        assertThat(window.comparedDays()).isEqualTo(7);
        assertThat(window.beforeStart()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(window.beforeEnd()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(window.afterStart()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(window.afterEnd()).isEqualTo(LocalDate.of(2026, 7, 17));
    }

    @Test
    void shortensBothSidesWhileAfterDataIsStillCollecting() {
        OperationWindowCalculator.EvaluationWindow window = calculator.calculate(
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 22),
                14
        );

        assertThat(window.state()).isEqualTo("COLLECTING");
        assertThat(window.comparedDays()).isEqualTo(2);
        assertThat(window.beforeStart()).isEqualTo(LocalDate.of(2026, 7, 18));
        assertThat(window.beforeEnd()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(window.afterStart()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(window.afterEnd()).isEqualTo(LocalDate.of(2026, 7, 22));
    }

    @Test
    void keepsRequestedBaselineAndWaitsWhenNoAfterDataExists() {
        OperationWindowCalculator.EvaluationWindow window = calculator.calculate(
                LocalDate.of(2026, 7, 24),
                LocalDate.of(2026, 7, 22),
                7
        );

        assertThat(window.state()).isEqualTo("WAITING");
        assertThat(window.comparedDays()).isZero();
        assertThat(window.beforeStart()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(window.beforeEnd()).isEqualTo(LocalDate.of(2026, 7, 23));
        assertThat(window.afterEnd()).isNull();
    }
}
