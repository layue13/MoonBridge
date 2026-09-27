package dev.moonbridge.luckperms;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ApiUserLeaseTrackerTest {
    @Test
    void cleanupWaitsForEveryLoadedAndPendingOwnerOfUuid() {
        List<String> cleaned = new ArrayList<>();
        ApiUserLeaseTracker<String> leases = new ApiUserLeaseTracker<>(cleaned::add);
        UUID player = UUID.randomUUID();
        var first = leases.acquire(player);
        var second = leases.acquire(player);

        first.loaded("first-api-user");
        first.close();
        assertEquals(List.of(), cleaned, "one remaining owner must retain UUID-wide API usage");

        second.loaded("second-api-user");
        second.close();
        assertEquals(List.of("second-api-user"), cleaned,
                "the last owner invokes UUID-wide cleanup once, using a loaded API wrapper");
        second.close();
        assertEquals(1, cleaned.size(), "release is idempotent");
    }

    @Test
    void closedPendingOwnerKeepsLeaseUntilLateLoadCanBeCleaned() {
        List<String> cleaned = new ArrayList<>();
        ApiUserLeaseTracker<String> leases = new ApiUserLeaseTracker<>(cleaned::add);
        UUID player = UUID.randomUUID();
        var pending = leases.acquire(player);

        pending.close();
        assertEquals(List.of(), cleaned, "an unfinished load may still register API usage");
        pending.loaded("late-api-user");
        assertEquals(List.of("late-api-user"), cleaned,
                "a late successful load after its owner closed is immediately unregistered");
    }

    @Test
    void lateLoadJoinsNewOwnersCleanupWindowAndFailedLoadAddsNoUsage() {
        List<String> cleaned = new ArrayList<>();
        ApiUserLeaseTracker<String> leases = new ApiUserLeaseTracker<>(cleaned::add);
        UUID player = UUID.randomUUID();
        var oldPending = leases.acquire(player);
        oldPending.close();

        var reconnect = leases.acquire(player);
        oldPending.loaded("old-api-user");
        reconnect.failed();
        reconnect.close();
        assertEquals(List.of("old-api-user"), cleaned,
                "a late registration is retained through a reconnect and cleaned after its final owner");

        var failed = leases.acquire(UUID.randomUUID());
        failed.failed();
        failed.close();
        assertEquals(1, cleaned.size(), "failed user loads did not register API usage");
    }
}
