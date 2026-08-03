package org.opengis.server.config;

import java.util.concurrent.ExecutorService;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.phase8.Phase8ExecutionBridge;
import org.opengis.server.phase8.Phase8Services;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.server.workflow.WorkflowApplicationService;
import org.opengis.tool.runtime.ToolRuntime;
import org.opengis.workflow.queue.AgentQueueService;
import org.opengis.workflow.queue.QueueRunPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Phase 6 composition root for Workflow and durable Agent queue services. */
@Configuration
public class Phase6WorkflowConfiguration {
  @Bean
  WorkflowApplicationService workflowApplicationService(
      ExecutorService agentExecutor,
      AgentApplicationService agents,
      ToolRuntime tools,
      UiRpcGateway ui,
      ObjectMapper mapper,
      Phase8Services phase8,
      Phase8ExecutionBridge phase8Bridge) {
    return new WorkflowApplicationService(
        agentExecutor, agents, tools, ui, mapper, phase8, phase8Bridge);
  }

  @Bean
  AgentQueueService agentQueueService(
      AgentApplicationService agents, WorkflowApplicationService workflows) {
    return new AgentQueueService(
        new QueueRunPort() {
          @Override
          public StartResult start(org.opengis.workflow.queue.AgentQueueItem item, boolean resume) {
            if (item.workflowId() != null && !item.workflowId().isBlank()) {
              var workflow =
                  new org.opengis.workflow.persistence.WorkflowStore(item.workspace())
                      .loadDocument(item.workflowId());
              if (workflow.isEmpty()) return new StartResult(false, "", "workflow not found");
              var result =
                  workflows.start(
                      item.workspace(),
                      workflow.get(),
                      item.conversationId(),
                      item.connectionId(),
                      resume,
                      resume ? item.runId() : "");
              return new StartResult(
                  "started".equals(result.get("status")),
                  String.valueOf(result.getOrDefault("run_id", "")),
                  String.valueOf(result.getOrDefault("message", "")));
            }
            var result =
                agents.start(
                    new AgentApplicationService.ChatCommand(
                        item.workspace(),
                        item.conversationId(),
                        item.connectionId(),
                        item.prompt(),
                        item.profileName(),
                        "",
                        128_000,
                        java.time.Duration.ofMinutes(10),
                        java.time.Duration.ofMinutes(2)));
            return new StartResult(
                "started".equals(result.get("status")),
                String.valueOf(result.getOrDefault("run_id", "")),
                String.valueOf(result.getOrDefault("message", "")));
          }

          @Override
          public boolean cancel(org.opengis.workflow.queue.AgentQueueItem item) {
            return item.workflowId() != null && !item.workflowId().isBlank()
                ? workflows.cancel(item.workspace(), item.runId())
                : "cancelled"
                    .equals(agents.interrupt(item.workspace(), item.runId()).get("status"));
          }

          @Override
          public String status(org.opengis.workflow.queue.AgentQueueItem item) {
            if (item.runId() == null || item.runId().isBlank()) return "unknown";
            if (item.workflowId() != null && !item.workflowId().isBlank()) {
              return workflows.status(item.workspace(), item.runId());
            }
            return RunArchive.load(item.workspace(), item.runId())
                .map(archive -> archive.meta().path("status").asText("unknown"))
                .orElse("unknown");
          }
        });
  }
}
