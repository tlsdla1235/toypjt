package com.sst.flaggame.domain.throne.controller;

import com.sst.flaggame.common.dto.ApiResponse;
import com.sst.flaggame.common.exception.BusinessException;
import com.sst.flaggame.common.exception.CooldownException;
import com.sst.flaggame.common.exception.ErrorCode;
import com.sst.flaggame.common.exception.GlobalExceptionHandler;
import com.sst.flaggame.common.security.AppUserPrincipal;
import com.sst.flaggame.domain.event.service.EventService;
import com.sst.flaggame.domain.throne.dto.ClaimResponse;
import com.sst.flaggame.domain.throne.service.ThroneService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThroneControllerTest {

    private final EventService eventService = mock(EventService.class);
    private final ThroneService throneService = mock(ThroneService.class);
    private final ThroneController throneController = new ThroneController(eventService, throneService);
    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void claimWhenNoActiveEventThenThrowsBusinessException() {
        AppUserPrincipal principal = new AppUserPrincipal(2L, "ADMIN");
        when(eventService.getCurrentActiveEventId()).thenReturn(null);

        assertThatThrownBy(() -> throneController.claim(principal))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NO_ACTIVE_EVENT);
    }

    @Test
    void claimWhenSuccessfulThenReturnsSuccessResponse() {
        AppUserPrincipal principal = new AppUserPrincipal(3L, "USER");
        when(eventService.getCurrentActiveEventId()).thenReturn(1L);
        when(throneService.claim(1L, 3L)).thenReturn(ClaimResponse.success(55L));

        ResponseEntity<ApiResponse<ClaimResponse>> response = throneController.claim(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("success");
        assertThat(response.getBody().getData().result()).isEqualTo("SUCCESS");
        assertThat(response.getBody().getData().reignId()).isEqualTo(55L);
    }

    @Test
    void cooldownExceptionHandlerIncludesRemainingMs() {
        CooldownException exception = new CooldownException(1234L);

        @SuppressWarnings("unchecked")
        ResponseEntity<?> response = ReflectionTestUtils.invokeMethod(
                globalExceptionHandler,
                "handleCooldownException",
                exception
        );

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).extracting("code").isEqualTo("COOLDOWN");
        assertThat(response.getBody()).extracting("remainingMs").isEqualTo(1234L);
    }
}
