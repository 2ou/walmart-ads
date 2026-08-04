package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.domain.ReplenishmentDecision;
import com.ai.walmartads.inventory.repository.ReplenishmentDecisionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplenishmentDecisionServiceTest {

    @Test
    void savesAConfirmedParentDecisionWithItsCalculationSnapshot() {
        ReplenishmentDecisionRepository repository = mock(ReplenishmentDecisionRepository.class);
        when(repository.findById("PNK366076")).thenReturn(Optional.empty());
        when(repository.save(any(ReplenishmentDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplenishmentDecisionService service = new ReplenishmentDecisionService(repository);

        var saved = service.update("PNK366076", new ReplenishmentDecisionService.DecisionPatch(
                320d, 348d, 300d, 45, "CONFIRMED", "优先补齐核心尺码"
        ));

        assertThat(saved.parentSku()).isEqualTo("PNK366076");
        assertThat(saved.finalQuantity()).isEqualTo(320d);
        assertThat(saved.systemSuggestedQuantity()).isEqualTo(348d);
        assertThat(saved.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void refusesConfirmationWithoutAFinalQuantity() {
        ReplenishmentDecisionRepository repository = mock(ReplenishmentDecisionRepository.class);
        ReplenishmentDecisionService service = new ReplenishmentDecisionService(repository);

        assertThatThrownBy(() -> service.update("PNK366076", new ReplenishmentDecisionService.DecisionPatch(
                null, 348d, 300d, 45, "CONFIRMED", null
        ))).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("最终补货数量");
    }

    @Test
    void acceptsAnIntegralFinalQuantityThatIsNotAMultipleOfFive() {
        ReplenishmentDecisionRepository repository = mock(ReplenishmentDecisionRepository.class);
        when(repository.findById("PNK366076")).thenReturn(Optional.empty());
        when(repository.save(any(ReplenishmentDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReplenishmentDecisionService service = new ReplenishmentDecisionService(repository);

        var saved = service.update("PNK366076", new ReplenishmentDecisionService.DecisionPatch(
                83d, 225d, 0d, 45, "CONFIRMED", null
        ));

        assertThat(saved.finalQuantity()).isEqualTo(83d);
    }

    @Test
    void requiresTheFinalQuantityToUseWholeUnits() {
        ReplenishmentDecisionRepository repository = mock(ReplenishmentDecisionRepository.class);
        ReplenishmentDecisionService service = new ReplenishmentDecisionService(repository);

        assertThatThrownBy(() -> service.update("PNK366076", new ReplenishmentDecisionService.DecisionPatch(
                83.5d, 225d, 0d, 45, "CONFIRMED", null
        ))).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("整数");
    }
}
