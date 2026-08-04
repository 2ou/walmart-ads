package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.domain.ParentLifecycle;
import com.ai.walmartads.inventory.repository.ParentLifecycleRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParentLifecycleServiceTest {

    @Test
    void savesClearanceAndAllSeasonTagsAtParentLevel() {
        ParentLifecycleRepository repository = mock(ParentLifecycleRepository.class);
        when(repository.findById("PNK366066")).thenReturn(Optional.empty());
        when(repository.save(any(ParentLifecycle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParentLifecycleService service = new ParentLifecycleService(repository);

        ParentLifecycleService.LifecycleView result = service.update("PNK366066", true, ParentLifecycle.ALL_SEASON);

        assertThat(result.parentSku()).isEqualTo("PNK366066");
        assertThat(result.clearance()).isTrue();
        assertThat(result.allSeason()).isTrue();
        assertThat(result.seasonType()).isEqualTo(ParentLifecycle.ALL_SEASON);
    }

    @Test
    void acceptsAutumnWinterAsASeasonTag() {
        ParentLifecycleRepository repository = mock(ParentLifecycleRepository.class);
        when(repository.findById("PNK366067")).thenReturn(Optional.empty());
        when(repository.save(any(ParentLifecycle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParentLifecycleService service = new ParentLifecycleService(repository);

        ParentLifecycleService.LifecycleView result = service.update("PNK366067", false, ParentLifecycle.AUTUMN_WINTER);

        assertThat(result.clearance()).isFalse();
        assertThat(result.allSeason()).isFalse();
        assertThat(result.seasonType()).isEqualTo(ParentLifecycle.AUTUMN_WINTER);
    }
}
