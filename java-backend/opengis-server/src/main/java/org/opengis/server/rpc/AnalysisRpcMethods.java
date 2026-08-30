package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import org.opengis.server.analysis.PivotAnalysisService;
import org.springframework.stereotype.Component;

/** Phase 9 UI-facing structured methods. */
@Component
public class AnalysisRpcMethods {
  private final RpcMethodRegistry methods;
  private final PivotAnalysisService pivot;

  public AnalysisRpcMethods(RpcMethodRegistry methods, PivotAnalysisService pivot) {
    this.methods = methods;
    this.pivot = pivot;
  }

  @PostConstruct
  void register() {
    methods.registerOrReplace("rpc.analysis.pivot", pivot::analyze);
  }
}
