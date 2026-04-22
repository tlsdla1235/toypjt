package com.sst.flaggame.domain.user.controller;

import com.sst.flaggame.common.dto.ApiResponse;
import com.sst.flaggame.common.security.AppUserPrincipal;
import com.sst.flaggame.domain.user.dto.UserMeResponse;
import com.sst.flaggame.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserMeResponse>> getMe(
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(userService.getMe(principal.userId())));
    }
}
