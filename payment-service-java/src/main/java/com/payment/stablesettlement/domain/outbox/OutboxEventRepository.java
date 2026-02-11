package com.payment.stablesettlement.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByPublishedFalseOrderByCreatedAtAsc();

    long countByPublishedFalse();

    @Modifying
    @Query(value = "DELETE FROM outbox_event WHERE published = true AND published_at < :cutoff LIMIT :batchSize",
            nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff,
                              @Param("batchSize") int batchSize);
}
