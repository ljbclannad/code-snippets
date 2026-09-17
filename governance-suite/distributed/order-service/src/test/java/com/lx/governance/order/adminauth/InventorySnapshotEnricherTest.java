package com.lx.governance.order.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.client.InventorySnapshotClient;
import com.lx.governance.order.client.InventorySnapshotClient.InventorySnapshotResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class InventorySnapshotEnricherTest {
    @Test
    void readsRepeatedProductsOnceAndEnrichesEveryMatchingOrder() {
        InventorySnapshotClient client = Mockito.mock(InventorySnapshotClient.class);
        InventorySnapshotEnricher enricher = new InventorySnapshotEnricher(client);
        Instant observedAt = Instant.parse("2026-09-17T10:00:00Z");
        when(client.snapshots(Mockito.any())).thenReturn(ApiResponse.ok(List.of(
                new InventorySnapshotResponse("P-1", 0, observedAt), new InventorySnapshotResponse("P-2", 8, observedAt)), null));

        List<AdminOrderSearchItem> result = enricher.enrich(List.of(item("O-1", "P-1"), item("O-2", "P-1"), item("O-3", "P-2")));

        ArgumentCaptor<InventorySnapshotClient.InventorySnapshotRequest> request = ArgumentCaptor.forClass(
                InventorySnapshotClient.InventorySnapshotRequest.class);
        verify(client).snapshots(request.capture());
        assertThat(request.getValue().productIds()).containsExactly("P-1", "P-2");
        assertThat(result).allSatisfy(item -> assertThat(item.inventorySnapshot().status()).isEqualTo("AVAILABLE"));
        assertThat(result.get(0).inventorySnapshot().available()).isZero();
    }

    @Test
    void returnsUnavailableWithoutBlockingOrdersWhenInventoryFails() {
        InventorySnapshotClient client = Mockito.mock(InventorySnapshotClient.class);
        InventorySnapshotEnricher enricher = new InventorySnapshotEnricher(client);
        when(client.snapshots(Mockito.any())).thenThrow(new IllegalStateException("timeout"));

        List<AdminOrderSearchItem> result = enricher.enrich(List.of(item("O-1", "P-1")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).inventorySnapshot().status()).isEqualTo("UNAVAILABLE");
        assertThat(result.get(0).inventorySnapshot().available()).isNull();
    }

    @Test
    void preservesAvailableSnapshotsWhenBatchResponseIsPartial() {
        InventorySnapshotClient client = Mockito.mock(InventorySnapshotClient.class);
        InventorySnapshotEnricher enricher = new InventorySnapshotEnricher(client);
        Instant observedAt = Instant.parse("2026-09-17T10:00:00Z");
        when(client.snapshots(Mockito.any())).thenReturn(ApiResponse.ok(List.of(
                new InventorySnapshotResponse("P-1", 8, observedAt)), null));

        List<AdminOrderSearchItem> result = enricher.enrich(List.of(item("O-1", "P-1"), item("O-2", "P-2")));

        assertThat(result).extracting(AdminOrderSearchItem::orderNo).containsExactly("O-1", "O-2");
        assertThat(result.get(0).inventorySnapshot().status()).isEqualTo("AVAILABLE");
        assertThat(result.get(0).inventorySnapshot().available()).isEqualTo(8);
        assertThat(result.get(1).inventorySnapshot().status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void returnsUnavailableWithoutBlockingOrdersWhenFallbackReturnsFailureResponse() {
        InventorySnapshotClient client = Mockito.mock(InventorySnapshotClient.class);
        InventorySnapshotEnricher enricher = new InventorySnapshotEnricher(client);
        when(client.snapshots(Mockito.any())).thenReturn(ApiResponse.fail("INVENTORY_UNAVAILABLE", "fallback", null));

        List<AdminOrderSearchItem> result = enricher.enrich(List.of(item("O-1", "P-1")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).inventorySnapshot().status()).isEqualTo("UNAVAILABLE");
    }

    private AdminOrderSearchItem item(String orderNo, String productId) {
        return new AdminOrderSearchItem(orderNo, "c***1", productId, 1, null, "CREATED", null, null, null,
                Instant.parse("2026-09-17T09:00:00Z"), null);
    }
}
