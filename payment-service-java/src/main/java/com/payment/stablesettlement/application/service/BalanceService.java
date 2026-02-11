package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BalanceService {

    private final UserBalanceRepository userBalanceRepository;

    @Transactional(readOnly = true)
    public UserBalance getBalance(Long userId) {
        return userBalanceRepository.findByUserId(userId)
                .orElseThrow(() -> new PaymentException("USER_NOT_FOUND",
                        "User not found: " + userId));
    }
}
