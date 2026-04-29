package com.sst.flaggame.domain.throne.service;

import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.event.repository.EventRepository;
import com.sst.flaggame.domain.throne.entity.CurrentThrone;
import com.sst.flaggame.domain.throne.entity.ThroneClaim;
import com.sst.flaggame.domain.throne.entity.ThroneReign;
import com.sst.flaggame.domain.throne.repository.CooldownRepository;
import com.sst.flaggame.domain.throne.repository.CurrentThroneRepository;
import com.sst.flaggame.domain.throne.repository.EventParticipantRepository;
import com.sst.flaggame.domain.throne.repository.ThroneClaimRepository;
import com.sst.flaggame.domain.throne.repository.ThroneReignRepository;
import com.sst.flaggame.domain.user.Role;
import com.sst.flaggame.domain.user.entity.User;
import com.sst.flaggame.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "spring.security.oauth2.client.registration.github.client-id=test-client",
        "spring.security.oauth2.client.registration.github.client-secret=test-secret",
        "spring.datasource.url=jdbc:mysql://localhost:3306/flaggame?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true",
        "spring.datasource.username=flaggame",
        "spring.datasource.password=flaggame_pw"
})
class ThroneServiceIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(10_000L);
    private static final long SYSTEM_USER_ID = 1L;

    @Autowired
    private ThroneService throneService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ThroneReignRepository throneReignRepository;

    @Autowired
    private CurrentThroneRepository currentThroneRepository;

    @Autowired
    private CooldownRepository cooldownRepository;

    @Autowired
    private ThroneClaimRepository throneClaimRepository;

    @Autowired
    private EventParticipantRepository eventParticipantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private AggregationCache aggregationCache;

    private final List<Long> createdEventIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clearInvocations(aggregationCache);
        clearPending();
    }

    @AfterEach
    void tearDown() {
        clearPending();
        cleanupCreatedData();
    }

    @Test
    void claimWhenCommittedThenCallsAggregationMethodsAfterCommit() {
        User claimant = createUser("claimant", Role.USER);
        Event event = createRunningEvent();
        createCurrentThrone(event, systemUser(), LocalDateTime.now().minusSeconds(5));

        throneService.claim(event.getId(), claimant.getId());

        verify(aggregationCache).addReignEnd(eq(event.getId()), eq(SYSTEM_USER_ID), org.mockito.ArgumentMatchers.longThat(v -> v >= 0));
        verify(aggregationCache).addClaimSuccess(event.getId(), claimant.getId());
    }

    @Test
    void claimWhenTransactionRollsBackThenDoesNotCallAggregationMethods() {
        User currentOwner = createUser("owner", Role.USER);
        User challenger = createUser("challenger", Role.USER);
        Event event = createRunningEvent();
        ThroneReign reign = createCurrentThrone(event, currentOwner, LocalDateTime.now().minusSeconds(5));
        jdbcTemplate.update(
                "update throne_reigns set ended_at = ?, duration_ms = ? where id = ?",
                LocalDateTime.now().minusSeconds(1),
                1_000L,
                reign.getId()
        );

        assertThatThrownBy(() -> throneService.claim(event.getId(), challenger.getId()))
                .isInstanceOf(IllegalStateException.class);

        verify(aggregationCache, never()).addReignEnd(anyLong(), anyLong(), anyLong());
        verify(aggregationCache, never()).addClaimSuccess(anyLong(), anyLong());
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

    private Event createRunningEvent() {
        User admin = createUser("admin", Role.ADMIN);
        Event event = Event.builder()
                .name("it-event-" + SEQUENCE.incrementAndGet())
                .cooldownMs(3_000)
                .durationH(1)
                .createdBy(admin)
                .build();
        event.start(LocalDateTime.now().minusMinutes(1));
        Event saved = eventRepository.save(event);
        createdEventIds.add(saved.getId());
        return saved;
    }

    private ThroneReign createCurrentThrone(Event event, User owner, LocalDateTime heldSince) {
        ThroneReign reign = throneReignRepository.save(new ThroneReign(event, owner, heldSince));
        jdbcTemplate.update(
                "insert into current_throne (event_id, reign_id, user_id, held_since) values (?, ?, ?, ?)",
                event.getId(),
                reign.getId(),
                owner.getId(),
                heldSince
        );
        return reign;
    }

    private User systemUser() {
        return userRepository.findById(SYSTEM_USER_ID)
                .orElseThrow(() -> new IllegalStateException("SYSTEM user missing"));
    }

    @SuppressWarnings("unchecked")
    private void clearPending() {
        ((java.util.concurrent.ConcurrentHashMap<AggregationCache.Key, AggregationCache.AggregationDelta>)
                org.springframework.test.util.ReflectionTestUtils.getField(aggregationCache, "pending")).clear();
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
        Mockito.reset(aggregationCache);
    }
}
