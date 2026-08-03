package org.opengis.server.rpc;

import java.util.Set;

/** Phase 0 inventory of methods accepted by the Python backend. */
public final class LegacyMethodCatalog {
  public static final Set<String> BACKEND_METHODS =
      Set.of(
          "chat.user_message",
          "rpc.agent.artifacts.list",
          "rpc.agent.inbox.list",
          "rpc.agent.interrupt",
          "rpc.agent.permissions.list",
          "rpc.agent.permissions.rules.add",
          "rpc.agent.permissions.rules.list",
          "rpc.agent.permissions.rules.remove",
          "rpc.agent.profiles.install_defaults",
          "rpc.agent.profiles.list",
          "rpc.agent.queue.cancel",
          "rpc.agent.queue.get",
          "rpc.agent.queue.list",
          "rpc.agent.queue.process",
          "rpc.agent.queue.resume",
          "rpc.agent.queue.retry",
          "rpc.agent.queue.run",
          "rpc.agent.queue.submit",
          "rpc.agent.sessions.list",
          "rpc.agent.set_llm_config",
          "rpc.agent.test_connection",
          "rpc.code.cancel_script",
          "rpc.code.run_script",
          "rpc.debug.get_log_level",
          "rpc.debug.set_log_level",
          "rpc.fs.get_file_info",
          "rpc.fs.load_file",
          "rpc.operations.get",
          "rpc.operations.list",
          "rpc.operations.run",
          "rpc.runs.get",
          "rpc.runs.list",
          "rpc.runs.replay",
          "rpc.tool.execute",
          "rpc.tool.list",
          "rpc.user_skill.add_source",
          "rpc.user_skill.list",
          "rpc.user_skill.load",
          "rpc.worker.delete",
          "rpc.worker.get",
          "rpc.worker.list",
          "rpc.worker.pause",
          "rpc.worker.restart",
          "rpc.workspace.install_templates",
          "rpc.workspace.revert_run",
          "user_instructions.get",
          "user_instructions.set");

  private LegacyMethodCatalog() {}
}
