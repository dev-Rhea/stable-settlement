package com.payment.stablesettlement.interfaces.controller;

import com.payment.stablesettlement.infrastructure.security.JwtTokenProvider;
import com.payment.stablesettlement.interfaces.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Profile({"dev", "test"})
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateToken(
            @RequestParam Long userId) {
        String token = jwtTokenProvider.generateToken(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("token", token)));
    }
}
