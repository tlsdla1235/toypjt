package com.sst.flaggame.domain.user.service;

import com.sst.flaggame.common.exception.BusinessException;
import com.sst.flaggame.common.exception.ErrorCode;
import com.sst.flaggame.domain.user.dto.UserMeResponse;
import com.sst.flaggame.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserMeResponse getMe(Long userId) {
        return userRepository.findById(userId)
                .map(UserMeResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
