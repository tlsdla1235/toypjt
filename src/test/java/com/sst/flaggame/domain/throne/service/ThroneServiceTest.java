package com.sst.flaggame.domain.throne.service;

import com.sst.flaggame.common.exception.BusinessException;
import com.sst.flaggame.common.exception.CooldownException;
import com.sst.flaggame.common.exception.ErrorCode;
import com.sst.flaggame.domain.event.entity.Event;
import com.sst.flaggame.domain.event.repository.EventRepository;
import com.sst.flaggame.domain.throne.dto.ClaimResponse;
import com.sst.flaggame.domain.throne.dto.ThroneState;
import com.sst.flaggame.domain.throne.entity.Cooldown;
import com.sst.flaggame.domain.throne.entity.CurrentThrone;
import com.sst.flaggame.domain.throne.entity.ThroneClaim;
import com.sst.flaggame.domain.throne.entity.ThroneClaimResult;
import com.sst.flaggame.domain.throne.entity.ThroneReign;
import com.sst.flaggame.domain.throne.repository.CooldownRepository;
import com.sst.flaggame.domain.throne.repository.CurrentThroneRepository;
import com.sst.flaggame.domain.throne.repository.ThroneClaimRepository;
import com.sst.flaggame.domain.throne.repository.ThroneReignRepository;
import com.sst.flaggame.domain.user.Role;
import com.sst.flaggame.domain.user.entity.User;
import com.sst.flaggame.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThroneServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ThroneReignRepository throneReignRepository;

    @Mock
    private CurrentThroneRepository currentThroneRepository;

    @Mock
    private CooldownRepository cooldownRepository;

    @Mock
    private ThroneClaimRepository throneClaimRepository;

    @Mock
    private AggregationCache aggregationCache;

    private ThroneService throneService;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);

        throneService = new ThroneService(
                eventRepository,
                userRepository,
                throneReignRepository,
                currentThroneRepository,
                cooldownRepository,
                throneClaimRepository,
                new TransactionTemplate(transactionManager),
                aggregationCache
        );
    }

    @Test
    void claimWhenCurrentOwnerThenThrowsAlreadyOwnerAndStoresAudit() {
        Long eventId = 10L;
        Long userId = 2L;
        setupCurrentThrone(eventId, createCurrentThrone(eventId, userId, 100L));
        when(cooldownRepository.findActive(eq(eventId), eq(userId), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(createRunningEvent(10_000)));

        assertThatThrownBy(() -> throneService.claim(eventId, userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ALREADY_OWNER);

        ArgumentCaptor<ThroneClaim> captor = ArgumentCaptor.forClass(ThroneClaim.class);
        verify(throneClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(ThroneClaimResult.ALREADY_OWNER);
        verify(cooldownRepository, times(1)).findActive(eq(eventId), eq(userId), any(LocalDateTime.class));
    }

    @Test
    void claimWhenCooldownActiveThenThrowsCooldownExceptionAndStoresAudit() {
        Long eventId = 11L;
        Long userId = 2L;
        setupCurrentThrone(eventId, createCurrentThrone(eventId, 99L, 101L));
        Cooldown cooldown = new Cooldown(eventId, userId, LocalDateTime.now().plusSeconds(5));
        when(cooldownRepository.findActive(eq(eventId), eq(userId), any(LocalDateTime.class)))
                .thenReturn(Optional.empty(), Optional.of(cooldown));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(createRunningEvent(10_000)));

        assertThatThrownBy(() -> throneService.claim(eventId, userId))
                .isInstanceOf(CooldownException.class)
                .satisfies(ex -> assertThat(((CooldownException) ex).getRemainingMs()).isPositive());

        ArgumentCaptor<ThroneClaim> captor = ArgumentCaptor.forClass(ThroneClaim.class);
        verify(throneClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(ThroneClaimResult.COOLDOWN);
        verify(cooldownRepository, times(2)).findActive(eq(eventId), eq(userId), any(LocalDateTime.class));
    }

    @Test
    void claimWhenEventNotRunningThenThrowsNotRunningAndStoresAudit() {
        Long eventId = 12L;
        Long userId = 2L;
        setupCurrentThrone(eventId, createCurrentThrone(eventId, 99L, 102L));
        when(cooldownRepository.findActive(eq(eventId), eq(userId), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(createEndedEvent(10_000)));

        assertThatThrownBy(() -> throneService.claim(eventId, userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_RUNNING);

        ArgumentCaptor<ThroneClaim> captor = ArgumentCaptor.forClass(ThroneClaim.class);
        verify(throneClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(ThroneClaimResult.NOT_RUNNING);
    }

    @Test
    void claimWhenSuccessfulThenReturnsSuccessAndUpdatesState() {
        Long eventId = 13L;
        Long userId = 2L;
        CurrentThrone currentThrone = createCurrentThrone(eventId, 99L, 103L);
        setupCurrentThrone(eventId, currentThrone);
        when(cooldownRepository.findActive(eq(eventId), eq(userId), any(LocalDateTime.class)))
                .thenReturn(Optional.empty(), Optional.empty());

        Event event = createRunningEvent(3_000);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        User newOwner = createUser(userId, Role.USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(newOwner));
        when(throneReignRepository.closeOpenReign(eq(103L), any(LocalDateTime.class), any(Long.class))).thenReturn(1);
        when(currentThroneRepository.findById(eventId)).thenReturn(Optional.of(currentThrone));
        when(throneReignRepository.save(any(ThroneReign.class))).thenAnswer(invocation -> {
            ThroneReign reign = invocation.getArgument(0);
            ReflectionTestUtils.setField(reign, "id", 200L);
            return reign;
        });
        when(throneClaimRepository.save(any(ThroneClaim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClaimResponse response = throneService.claim(eventId, userId);

        assertThat(response.result()).isEqualTo("SUCCESS");
        assertThat(response.reignId()).isEqualTo(200L);
        assertThat(currentThrone.getUser().getId()).isEqualTo(userId);
        assertThat(currentThrone.getReign().getId()).isEqualTo(200L);

        verify(throneReignRepository).closeOpenReign(eq(103L), any(LocalDateTime.class), any(Long.class));
        verify(cooldownRepository).upsert(eq(eventId), eq(userId), any(LocalDateTime.class));
        verify(aggregationCache).addReignEnd(eq(eventId), eq(99L), any(Long.class));
        verify(aggregationCache).addClaimSuccess(eventId, userId);

        ArgumentCaptor<ThroneClaim> captor = ArgumentCaptor.forClass(ThroneClaim.class);
        verify(throneClaimRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(ThroneClaimResult.SUCCESS);

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, ThroneState> throneMap =
                (ConcurrentHashMap<Long, ThroneState>) ReflectionTestUtils.getField(throneService, "throneMap");
        assertThat(throneMap).containsKey(eventId);
        assertThat(throneMap.get(eventId).currentKingId()).isEqualTo(userId);
        assertThat(throneMap.get(eventId).reignId()).isEqualTo(200L);
    }

    @Test
    void claimWhenFailureThenDoesNotCallAggregationCache() {
        Long eventId = 14L;
        Long userId = 2L;
        setupCurrentThrone(eventId, createCurrentThrone(eventId, userId, 104L));
        when(cooldownRepository.findActive(eq(eventId), eq(userId), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(createRunningEvent(10_000)));

        assertThatThrownBy(() -> throneService.claim(eventId, userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ALREADY_OWNER);

        verify(aggregationCache, org.mockito.Mockito.never()).addReignEnd(any(), any(), any(Long.class));
        verify(aggregationCache, org.mockito.Mockito.never()).addClaimSuccess(any(), any());
    }

    private void setupCurrentThrone(Long eventId, CurrentThrone currentThrone) {
        when(currentThroneRepository.findDetailByEventId(eventId)).thenReturn(Optional.of(currentThrone));
    }

    private CurrentThrone createCurrentThrone(Long eventId, Long currentUserId, Long reignId) {
        Event event = createRunningEvent(10_000);
        ReflectionTestUtils.setField(event, "id", eventId);

        User currentUser = createUser(currentUserId, Role.USER);
        ThroneReign reign = new ThroneReign(event, currentUser, LocalDateTime.now().minusSeconds(5));
        ReflectionTestUtils.setField(reign, "id", reignId);

        return new CurrentThrone(event, reign, currentUser, LocalDateTime.now().minusSeconds(5));
    }

    private Event createRunningEvent(int cooldownMs) {
        User admin = createUser(900L, Role.ADMIN);
        Event event = Event.builder()
                .name("test-event")
                .cooldownMs(cooldownMs)
                .durationH(1)
                .createdBy(admin)
                .build();
        event.start(LocalDateTime.now().minusMinutes(1));
        return event;
    }

    private Event createEndedEvent(int cooldownMs) {
        Event event = createRunningEvent(cooldownMs);
        event.end(LocalDateTime.now().minusSeconds(1));
        return event;
    }

    private User createUser(Long id, Role role) {
        User user = User.builder()
                .githubId(id + 1_000)
                .login("user-" + id)
                .avatarUrl(null)
                .role(role)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
