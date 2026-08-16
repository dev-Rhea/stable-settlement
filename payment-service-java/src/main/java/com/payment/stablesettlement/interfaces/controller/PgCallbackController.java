package com.payment.stablesettlement.interfaces.controller;

import com.payment.stablesettlement.application.service.PgCallbackService;
import com.payment.stablesettlement.interfaces.dto.ApiResponse;
import com.payment.stablesettlement.interfaces.dto.pg.PgCallbackRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/pg-callback")
@RequiredArgsConstructor
public class PgCallbackController {

    private final PgCallbackService pgCallbackService;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> handleCallback(@Valid @RequestBody PgCallbackRequest request) {
        pgCallbackService.handleCallback(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
