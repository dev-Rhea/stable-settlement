package com.payment.reconciliation.job;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

/**
 * 대사 작업 항목을 ID 구간으로 잘라 워커 Step 에 나눠준다.
 * 항목이 없으면 워커도 만들지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class WorkItemRangePartitioner implements Partitioner {

    private final ReconciliationRepository reconciliationRepository;
    private final LocalDate reconciliationDate;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>();

        Long minId = reconciliationRepository.minWorkItemId(reconciliationDate);
        Long maxId = reconciliationRepository.maxWorkItemId(reconciliationDate);

        if (minId == null || maxId == null) {
            log.info("No reconciliation work items to partition: date={}", reconciliationDate);
            return partitions;
        }

        long total = maxId - minId + 1;
        long targetSize = Math.max(1, (total + gridSize - 1) / gridSize);

        long start = minId;
        int index = 0;
        while (start <= maxId) {
            long end = Math.min(start + targetSize - 1, maxId);

            ExecutionContext context = new ExecutionContext();
            context.putLong("minId", start);
            context.putLong("maxId", end);
            partitions.put("partition" + index, context);

            start = end + 1;
            index++;
        }

        log.info("Partitioned reconciliation work items: date={}, range=[{}, {}], partitions={}",
            reconciliationDate, minId, maxId, partitions.size());

        return partitions;
    }
}
