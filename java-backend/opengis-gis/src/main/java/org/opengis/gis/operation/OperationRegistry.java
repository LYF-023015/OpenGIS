package org.opengis.gis.operation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.opengis.gis.operation.builtin.AdvancedClusteringOperation;
import org.opengis.gis.operation.builtin.FormatConverterOperation;
import org.opengis.gis.operation.builtin.KernelDensityOperation;
import tools.jackson.databind.ObjectMapper;

/** Deterministic registry for the three trusted Phase 8A GIS operations. */
public final class OperationRegistry {
  private final Map<String, BuiltinOperation> values = new LinkedHashMap<>();

  public OperationRegistry(ObjectMapper mapper) {
    register(new FormatConverterOperation(mapper));
    register(new AdvancedClusteringOperation(mapper));
    register(new KernelDensityOperation(mapper));
  }

  public Optional<BuiltinOperation> find(String id) {
    return Optional.ofNullable(values.get(id));
  }

  public List<BuiltinOperation> all() {
    return List.copyOf(values.values());
  }

  private void register(BuiltinOperation operation) {
    if (values.putIfAbsent(operation.id(), operation) != null) {
      throw new IllegalArgumentException("Duplicate operation: " + operation.id());
    }
  }
}
