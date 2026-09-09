package payment.mockpg.controller;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import payment.mockpg.service.PgSettlementExporter;

@RestController
@RequestMapping("/pg/settlements")
@RequiredArgsConstructor
public class PgSettlementController {

    private final PgSettlementExporter pgSettlementExporter;

    @GetMapping(value = "/{date}", produces = "text/csv")
    public ResponseEntity<String> exportSettlements(
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(pgSettlementExporter.exportCsv(date));
    }
}
