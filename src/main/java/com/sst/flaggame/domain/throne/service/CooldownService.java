package com.sst.flaggame.domain.throne.service;

import com.sst.flaggame.domain.throne.repository.CooldownRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CooldownService {

    private final CooldownRepository cooldownRepository;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void purgeExpired() {
        cooldownRepository.deleteExpired(LocalDateTime.now().minusSeconds(10));
    }
}
