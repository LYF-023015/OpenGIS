package org.opengis.platform.persistence;

import java.util.List;

/** Aggregate report produced before any Java writer or migration is enabled. */
public record WorkspaceCompatibilityReport(List<StoreInspection> stores) {
  public boolean compatible() {
    return stores.stream().allMatch(store -> !store.required() || store.readable());
  }

  public int readableStoreCount() {
    return (int) stores.stream().filter(StoreInspection::readable).count();
  }

  public int persistentStoreCount() {
    return (int) stores.stream().filter(StoreInspection::required).count();
  }
}
