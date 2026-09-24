package dev.strataproxy.core.backend;

import java.util.List;
import java.util.Optional;

/** Shared directory of static and plugin-owned backend registrations. */
public interface BackendCatalog {
    BackendView register(BackendRegistration registration);

    Optional<BackendView> update(BackendHandle handle, BackendRegistration registration);

    boolean remove(BackendHandle handle);

    int removeOwner(BackendOwner owner);

    Optional<BackendView> find(BackendId id);

    List<BackendView> snapshot();

    Optional<CapacityReservation> reserve(BackendHandle handle, int units);
}
