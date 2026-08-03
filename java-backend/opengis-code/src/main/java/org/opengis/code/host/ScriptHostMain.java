package org.opengis.code.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.opengis.script.sdk.OpenGisScript;
import org.opengis.script.sdk.OpenGisWorker;
import org.opengis.script.sdk.ProtocolTransport;
import org.opengis.script.sdk.ScriptContext;
import org.opengis.script.sdk.ScriptProtocol;
import org.opengis.script.sdk.WorkerContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bundled child-JVM host. Its stdout is reserved for versioned JSONL protocol frames. */
public final class ScriptHostMain {
  private ScriptHostMain() {}

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Entry class argument is required");
    ObjectMapper mapper = new ObjectMapper();
    BufferedReader input =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintWriter protocolOutput = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    String raw = input.readLine();
    if (raw == null) throw new IllegalStateException("Parent closed before execute");
    JsonNode execute = mapper.readTree(raw);
    if (!"execute".equals(execute.path("type").asText()))
      throw new IllegalArgumentException("First frame must be execute");
    String runId = execute.path("run_id").asText();
    Path workspace = Path.of(execute.path("workspace").asText()).toAbsolutePath().normalize();
    Map<String, Object> parameters = mapper.convertValue(execute.path("parameters"), Map.class);
    HostTransport transport = new HostTransport(runId, input, protocolOutput, mapper);
    Thread execution = Thread.currentThread();
    transport.startReader(execution);
    System.setOut(
        new PrintStream(new LineEventOutput(transport, "stdout"), true, StandardCharsets.UTF_8));
    System.setErr(
        new PrintStream(new LineEventOutput(transport, "stderr"), true, StandardCharsets.UTF_8));
    transport.emit("started", Map.of("pid", ProcessHandle.current().pid()));
    try {
      Class<?> type = Class.forName(args[0]);
      Object instance = type.getDeclaredConstructor().newInstance();
      Object output;
      if (instance instanceof OpenGisScript script) {
        output = script.run(new ScriptContext(workspace, runId, transport), parameters);
      } else if (instance instanceof OpenGisWorker worker) {
        try {
          worker.start(new WorkerContext(workspace, runId, parameters, transport));
          output = worker.health();
        } finally {
          worker.stop();
        }
      } else {
        throw new IllegalArgumentException(
            "Entry class must implement OpenGisScript or OpenGisWorker: " + args[0]);
      }
      transport.emit("completed", Map.of("output", output == null ? Map.of() : output));
    } catch (Throwable exception) {
      String status = transport.isCancelled() ? "cancelled" : "failed";
      transport.emit(
          status,
          Map.of(
              "error",
              exception.getMessage() == null
                  ? exception.getClass().getName()
                  : exception.getMessage(),
              "exception",
              exception.getClass().getName()));
      System.exit(transport.isCancelled() ? 130 : 1);
    } finally {
      transport.close();
    }
  }

  private static final class HostTransport implements ProtocolTransport {
    private final String runId;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ObjectMapper mapper;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pending =
        new ConcurrentHashMap<>();

    private HostTransport(
        String runId, BufferedReader input, PrintWriter output, ObjectMapper mapper) {
      this.runId = runId;
      this.input = input;
      this.output = output;
      this.mapper = mapper;
    }

    void startReader(Thread execution) {
      Thread.ofPlatform()
          .daemon()
          .name("opengis-script-parent-reader")
          .start(
              () -> {
                try {
                  String line;
                  while ((line = input.readLine()) != null) {
                    JsonNode message = mapper.readTree(line);
                    String type = message.path("type").asText();
                    if ("cancel".equals(type) || "shutdown".equals(type)) {
                      cancelled.set(true);
                      execution.interrupt();
                    } else if ("request_result".equals(type)) {
                      String callId = message.path("call_id").asText();
                      CompletableFuture<Map<String, Object>> future = pending.remove(callId);
                      if (future != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload =
                            mapper.convertValue(message.path("payload"), Map.class);
                        if (message.path("success").asBoolean(false)) future.complete(payload);
                        else
                          future.completeExceptionally(
                              new IllegalStateException(
                                  message.path("error").asText("Parent request failed")));
                      }
                    }
                  }
                  cancelled.set(true);
                  execution.interrupt();
                } catch (Exception exception) {
                  cancelled.set(true);
                  execution.interrupt();
                }
              });
    }

    @Override
    public Map<String, Object> request(String type, Map<String, Object> payload) {
      String callId = UUID.randomUUID().toString();
      CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
      pending.put(callId, future);
      write(type, callId, payload);
      try {
        return future.get(120, TimeUnit.SECONDS);
      } catch (Exception exception) {
        pending.remove(callId);
        throw new IllegalStateException("Parent callback failed: " + type, exception);
      }
    }

    @Override
    public void emit(String type, Map<String, Object> payload) {
      write(type, "", payload);
    }

    private void write(String type, String callId, Map<String, Object> payload) {
      ObjectNode message = mapper.createObjectNode();
      message.put("protocol_version", ScriptProtocol.VERSION);
      message.put("type", type);
      message.put("run_id", runId);
      message.put("call_id", callId);
      message.put("sequence", sequence.incrementAndGet());
      message.set("payload", mapper.valueToTree(payload == null ? Map.of() : payload));
      synchronized (output) {
        output.println(mapper.writeValueAsString(message));
        output.flush();
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    void close() {
      pending.values().forEach(value -> value.cancel(true));
      pending.clear();
    }
  }

  private static final class LineEventOutput extends OutputStream {
    private final HostTransport transport;
    private final String type;
    private final StringBuilder line = new StringBuilder();

    private LineEventOutput(HostTransport transport, String type) {
      this.transport = transport;
      this.type = type;
    }

    @Override
    public synchronized void write(int value) throws IOException {
      if (value == '\n') flush();
      else if (value != '\r' && line.length() < 65_536) line.append((char) value);
    }

    @Override
    public synchronized void flush() {
      if (!line.isEmpty()) {
        transport.emit(type, Map.of("text", line.toString()));
        line.setLength(0);
      }
    }
  }
}
