package com.payment.stablesettlement.interfaces.controller;

import com.payment.stablesettlement.application.service.BalanceService;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.interfaces.dto.ApiResponse;
import com.payment.stablesettlement.interfaces.dto.BalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/balances")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(
            @AuthenticationPrincipal Long authenticatedUserId,
            @PathVariable Long userId) {
        if (!userId.equals(authenticatedUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Cannot access another user's balance"));
        }

        UserBalance balance = balanceService.getBalance(userId);
        return ResponseEntity.ok(ApiResponse.success(BalanceResponse.from(balance)));
    }
}
