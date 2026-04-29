package com.sst.flaggame.domain.throne.service;

import com.sst.flaggame.domain.throne.repository.CooldownRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CooldownServiceTest {

    private final CooldownRepository cooldownRepository = mock(CooldownRepository.class);
    private final CooldownService cooldownService = new CooldownService(cooldownRepository);

    @Test
    void purgeExpiredDeletesRowsUsingNowMinusTenSecondsThreshold() {
        LocalDateTime before = LocalDateTime.now();

        cooldownService.purgeExpired();

        LocalDateTime after = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(cooldownRepository).deleteExpired(captor.capture());

        Duration lower = Duration.between(before.minusSeconds(10), captor.getValue());
        Duration upper = Duration.between(captor.getValue(), after.minusSeconds(10));
        assertThat(lower.isNegative()).isFalse();
        assertThat(upper.isNegative()).isFalse();
    }
}
