package payment.mockpg.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import payment.mockpg.dto.PgApprovalRequest;
import payment.mockpg.dto.PgApprovalResponse;
import payment.mockpg.service.PgApprovalService;

@RestController
@RequestMapping("/pg")
@RequiredArgsConstructor
public class PgApprovalController {

    private final PgApprovalService pgApprovalService;

    @PostMapping("/approve")
    public ResponseEntity<PgApprovalResponse> approve(@Valid @RequestBody PgApprovalRequest request) {
        String pgTransactionId = pgApprovalService.approve(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PgApprovalResponse(pgTransactionId));
    }
}
