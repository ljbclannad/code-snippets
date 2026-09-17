package com.lx.governance.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.lx.governance.inventory.domain.InventorySnapshotBatchRequest;
import com.lx.governance.inventory.repo.StockRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

class InventorySnapshotServiceTest {
    @Test
    void deduplicatesProductsAndReturnsCurrentAvailableStock() {
        StockRepository repository = Mockito.mock(StockRepository.class);
        when(repository.findByProductIds(List.of("P-1", "P-2"))).thenReturn(List.of(
                new StockRepository.StockRow("P-1", 0, 1), new StockRepository.StockRow("P-2", 8, 2)));
        InventoryAppService service = service(repository);

        var snapshots = service.snapshots(new InventorySnapshotBatchRequest(List.of("P-1", "P-1", "P-2")));

        ArgumentCaptor<List<String>> productIds = ArgumentCaptor.forClass(List.class);
        verify(repository).findByProductIds(productIds.capture());
        assertThat(productIds.getValue()).containsExactly("P-1", "P-2");
        assertThat(snapshots).extracting(snapshot -> snapshot.available()).containsExactly(0, 8);
        assertThat(snapshots).allSatisfy(snapshot -> assertThat(snapshot.observedAt()).isNotNull());
    }

    @Test
    void rejectsMoreThanOneHundredUniqueProducts() {
        InventoryAppService service = service(Mockito.mock(StockRepository.class));
        List<String> productIds = java.util.stream.IntStream.range(0, 101).mapToObj(index -> "P-" + index).toList();

        assertThatThrownBy(() -> service.snapshots(new InventorySnapshotBatchRequest(productIds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("productIds must contain between 1 and 100 unique values");
    }

    private InventoryAppService service(StockRepository repository) {
        return new InventoryAppService(repository, Mockito.mock(StringRedisTemplate.class), false, Mockito.mock(MeterRegistry.class));
    }
}
