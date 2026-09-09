package com.payment.reconciliation.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;

@ExtendWith(MockitoExtension.class)
class WorkItemRangePartitionerTest {

    private static final LocalDate DATE = LocalDate.of(2025, 1, 15);

    @Mock
    private ReconciliationRepository reconciliationRepository;

    private WorkItemRangePartitioner partitioner() {
        return new WorkItemRangePartitioner(reconciliationRepository, DATE);
    }

    private void givenIdRange(Long min, Long max) {
        when(reconciliationRepository.minWorkItemId(DATE)).thenReturn(min);
        when(reconciliationRepository.maxWorkItemId(DATE)).thenReturn(max);
    }

    @Test
    @DisplayName("ID 구간을 gridSize만큼 나누고 구간이 서로 겹치지 않는다")
    void splitsRangeWithoutOverlap() {
        givenIdRange(1L, 100L);

        Map<String, ExecutionContext> partitions = partitioner().partition(4);

        assertThat(partitions).hasSize(4);

        long covered = 0;
        long previousEnd = 0;
        for (int i = 0; i < 4; i++) {
            ExecutionContext context = partitions.get("partition" + i);
            long min = context.getLong("minId");
            long max = context.getLong("maxId");

            assertThat(min).isEqualTo(previousEnd + 1);
            assertThat(max).isGreaterThanOrEqualTo(min);
            covered += max - min + 1;
            previousEnd = max;
        }

        assertThat(previousEnd).isEqualTo(100L);
        assertThat(covered).isEqualTo(100L);
    }

    @Test
    @DisplayName("항목 수가 gridSize보다 적으면 파티션도 그만큼만 만든다")
    void createsFewerPartitionsThanGridSize() {
        givenIdRange(7L, 8L);

        Map<String, ExecutionContext> partitions = partitioner().partition(4);

        assertThat(partitions).hasSize(2);
        assertThat(partitions.get("partition0").getLong("minId")).isEqualTo(7L);
        assertThat(partitions.get("partition1").getLong("maxId")).isEqualTo(8L);
    }

    @Test
    @DisplayName("대사할 항목이 없으면 워커를 만들지 않는다")
    void createsNoPartitionWhenEmpty() {
        givenIdRange(null, null);

        assertThat(partitioner().partition(4)).isEmpty();
    }
}
