package com.sst.flaggame.domain.event.service;

import com.sst.flaggame.domain.event.dto.EventResponse;
import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.throne.entity.ThroneReign;
import com.sst.flaggame.domain.throne.service.ThroneService;
import com.sst.flaggame.domain.user.Role;
import com.sst.flaggame.domain.user.entity.User;
import com.sst.flaggame.domain.user.repository.UserRepository;
import com.sst.flaggame.domain.event.repository.EventRepository;
import com.sst.flaggame.domain.throne.repository.ThroneReignRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "spring.security.oauth2.client.registration.github.client-id=test-client",
        "spring.security.oauth2.client.registration.github.client-secret=test-secret",
        "spring.datasource.url=jdbc:mysql://localhost:3306/flaggame?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true",
        "spring.datasource.username=flaggame",
        "spring.datasource.password=flaggame_pw"
})
class EventServiceIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(20_000L);
    private static final long SYSTEM_USER_ID = 1L;

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ThroneReignRepository throneReignRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ThroneService throneService;

    private final List<Long> createdEventIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clearActiveCache();
        clearInvocations(throneService);
    }

    @AfterEach
    void tearDown() {
        clearActiveCache();
        cleanupCreatedData();
    }

    @Test
    void getCurrentActiveEventIdWhenCacheMissingThenRestoresRunningEventFromDb() {
        Event runningEvent = createRunningEvent();
        createCurrentThrone(runningEvent, systemUser(), LocalDateTime.now().minusSeconds(3));

        Long activeEventId = eventService.getCurrentActiveEventId();
        EventResponse activeEvent = eventService.getActiveEvent();

        assertThat(activeEventId).isEqualTo(runningEvent.getId());
        assertThat(activeEvent.id()).isEqualTo(runningEvent.getId());
        assertThat(readActiveCache()).isEqualTo(runningEvent.getId());
        verify(throneService).restoreRunningEvent(runningEvent.getId());
    }

    @Test
    void getActiveEventWhenCacheIsStaleThenFallsBackToRunningEvent() {
        Event endedEvent = createEndedEvent();
        Event runningEvent = createRunningEvent();
        createCurrentThrone(runningEvent, systemUser(), LocalDateTime.now().minusSeconds(3));
        setActiveCache(endedEvent.getId());

        EventResponse activeEvent = eventService.getActiveEvent();

        assertThat(activeEvent.id()).isEqualTo(runningEvent.getId());
        assertThat(readActiveCache()).isEqualTo(runningEvent.getId());
        verify(throneService).restoreRunningEvent(runningEvent.getId());
    }

    private Event createRunningEvent() {
        User admin = createUser("admin", Role.ADMIN);
        Event event = Event.builder()
                .name("event-it-" + SEQUENCE.incrementAndGet())
                .cooldownMs(3_000)
                .durationH(1)
                .createdBy(admin)
                .build();
        event.start(LocalDateTime.now().minusMinutes(1));
        Event saved = eventRepository.save(event);
        createdEventIds.add(saved.getId());
        return saved;
    }

    private Event createEndedEvent() {
        User admin = createUser("ended-admin", Role.ADMIN);
        Event event = Event.builder()
                .name("ended-it-" + SEQUENCE.incrementAndGet())
                .cooldownMs(3_000)
                .durationH(1)
                .createdBy(admin)
                .build();
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(10);
        event.start(startedAt);
        event.end(startedAt.plusMinutes(5));
        Event saved = eventRepository.save(event);
        createdEventIds.add(saved.getId());
        return saved;
    }

    private void createCurrentThrone(Event event, User owner, LocalDateTime heldSince) {
        ThroneReign reign = throneReignRepository.save(new ThroneReign(event, owner, heldSince));
        jdbcTemplate.update(
                "insert into current_throne (event_id, reign_id, user_id, held_since) values (?, ?, ?, ?)",
                event.getId(),
                reign.getId(),
                owner.getId(),
                heldSince
        );
    }

    private User createUser(String prefix, Role role) {
        long seed = SEQUENCE.incrementAndGet();
        User user = userRepository.save(User.builder()
                .githubId(seed)
                .login(prefix + "-" + seed)
                .avatarUrl(null)
                .role(role)
                .build());
        createdUserIds.add(user.getId());
        return user;
    }

    private User systemUser() {
        return userRepository.findById(SYSTEM_USER_ID)
                .orElseThrow(() -> new IllegalStateException("SYSTEM user missing"));
    }

    @SuppressWarnings("unchecked")
    private void clearActiveCache() {
        ((AtomicReference<Long>) ReflectionTestUtils.getField(eventService, "currentActiveEventId")).set(null);
    }

    @SuppressWarnings("unchecked")
    private Long readActiveCache() {
        return ((AtomicReference<Long>) ReflectionTestUtils.getField(eventService, "currentActiveEventId")).get();
    }

    @SuppressWarnings("unchecked")
    private void setActiveCache(Long eventId) {
        ((AtomicReference<Long>) ReflectionTestUtils.getField(eventService, "currentActiveEventId")).set(eventId);
    }

    private void cleanupCreatedData() {
        for (Long eventId : createdEventIds) {
            jdbcTemplate.update("delete from leaderboard_snapshot where event_id = ?", eventId);
            jdbcTemplate.update("delete from cooldowns where event_id = ?", eventId);
            jdbcTemplate.update("delete from throne_claims where event_id = ?", eventId);
            jdbcTemplate.update("delete from current_throne where event_id = ?", eventId);
            jdbcTemplate.update("delete from throne_reigns where event_id = ?", eventId);
            jdbcTemplate.update("delete from event_participants where event_id = ?", eventId);
            jdbcTemplate.update("delete from events where id = ?", eventId);
        }

        for (Long userId : createdUserIds) {
            jdbcTemplate.update("delete from users where id = ?", userId);
        }

        createdEventIds.clear();
        createdUserIds.clear();
        Mockito.reset(throneService);
    }
}
