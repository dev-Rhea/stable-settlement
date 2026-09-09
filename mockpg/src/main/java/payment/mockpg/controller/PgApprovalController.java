package payment.mockpg.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import payment.mockpg.dto.PgApprovalRequest;
import payment.mockpg.dto.PgApprovalResponse;
import payment.mockpg.dto.PgStatusResponse;
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

    /**
     * 콜백이 유실된 경우 결제 서비스가 승인 결과를 다시 확인할 수 있게 하는 재조회 API.
     */
    @GetMapping("/transactions/{pgTransactionId}")
    public ResponseEntity<PgStatusResponse> findStatus(@PathVariable String pgTransactionId) {
        return pgApprovalService.findStatus(pgTransactionId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
