/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic plugin dependency resolver and lifecycle owner. */
public final class PluginRuntime implements AutoCloseable {
  private final PluginContext context;
  private final Map<String, OpenGisPlugin> plugins;
  private final PluginProfile profile;
  private final LinkedHashMap<String, PluginHandle> mounted = new LinkedHashMap<>();
  private boolean started;

  public PluginRuntime(PluginContext context, Collection<? extends OpenGisPlugin> plugins) {
    this(context, plugins, PluginProfile.all());
  }

  public PluginRuntime(
      PluginContext context, Collection<? extends OpenGisPlugin> plugins, PluginProfile profile) {
    this.context = Objects.requireNonNull(context, "context");
    this.profile = Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(plugins, "plugins");
    LinkedHashMap<String, OpenGisPlugin> indexed = new LinkedHashMap<>();
    for (OpenGisPlugin plugin : plugins) {
      String id = plugin.descriptor().id();
      if (indexed.putIfAbsent(id, plugin) != null) {
        throw new PluginLifecycleException("Duplicate plugin id: " + id);
      }
    }
    this.plugins = Map.copyOf(indexed);
  }

  public synchronized PluginRuntime start() {
    if (started) {
      return this;
    }
    List<OpenGisPlugin> ordered = resolveOrder();
    try {
      for (OpenGisPlugin plugin : ordered) {
        PluginHandle handle = plugin.mount(context);
        mounted.put(plugin.descriptor().id(), handle == null ? PluginHandle.noop() : handle);
      }
      started = true;
      return this;
    } catch (RuntimeException exception) {
      rollback(exception);
      throw new PluginLifecycleException("Failed to start plugin runtime", exception);
    }
  }

  public synchronized boolean isStarted() {
    return started;
  }

  public synchronized List<String> mountedPluginIds() {
    return List.copyOf(mounted.keySet());
  }

  @Override
  public synchronized void close() {
    RuntimeException failure = closeMounted();
    started = false;
    if (failure != null) {
      throw failure;
    }
  }

  private List<OpenGisPlugin> resolveOrder() {
    Set<String> selected = selectedPlugins();
    List<OpenGisPlugin> ordered = new ArrayList<>();
    Map<String, Visit> visits = new HashMap<>();
    Deque<String> path = new ArrayDeque<>();
    selected.stream().sorted().forEach(id -> visit(id, selected, visits, path, ordered));
    return List.copyOf(ordered);
  }

  private Set<String> selectedPlugins() {
    if (profile.enabledPlugins().isEmpty()) {
      return plugins.keySet();
    }
    LinkedHashSet<String> selected = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>(profile.enabledPlugins());
    while (!pending.isEmpty()) {
      String id = pending.removeFirst();
      OpenGisPlugin plugin = requirePlugin(id, "profile " + profile.id());
      if (selected.add(id)) {
        pending.addAll(plugin.descriptor().requires());
      }
    }
    return Set.copyOf(selected);
  }

  private void visit(
      String id,
      Set<String> selected,
      Map<String, Visit> visits,
      Deque<String> path,
      List<OpenGisPlugin> ordered) {
    Visit state = visits.get(id);
    if (state == Visit.DONE) {
      return;
    }
    if (state == Visit.ACTIVE) {
      throw new PluginLifecycleException("Plugin dependency cycle: " + cycle(path, id));
    }
    OpenGisPlugin plugin = requirePlugin(id, "plugin dependency graph");
    visits.put(id, Visit.ACTIVE);
    path.addLast(id);
    plugin.descriptor().requires().stream()
        .sorted()
        .forEach(
            dependency -> {
              requirePlugin(dependency, "plugin " + id);
              if (!selected.contains(dependency)) {
                throw new PluginLifecycleException(
                    "Plugin " + id + " requires disabled plugin " + dependency);
              }
              visit(dependency, selected, visits, path, ordered);
            });
    path.removeLast();
    visits.put(id, Visit.DONE);
    ordered.add(plugin);
  }

  private OpenGisPlugin requirePlugin(String id, String source) {
    OpenGisPlugin plugin = plugins.get(id);
    if (plugin == null) {
      throw new PluginLifecycleException("Unknown plugin " + id + " referenced by " + source);
    }
    return plugin;
  }

  private static String cycle(Deque<String> path, String repeated) {
    List<String> values = new ArrayList<>(path);
    int start = values.indexOf(repeated);
    List<String> cycle = new ArrayList<>(values.subList(Math.max(0, start), values.size()));
    cycle.add(repeated);
    return String.join(" -> ", cycle);
  }

  private void rollback(RuntimeException original) {
    RuntimeException failure = closeMounted();
    if (failure != null) {
      original.addSuppressed(failure);
    }
    started = false;
  }

  private RuntimeException closeMounted() {
    List<PluginHandle> handles = new ArrayList<>(mounted.values());
    mounted.clear();
    try {
      PluginHandle.combine(handles).close();
      return null;
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private enum Visit {
    ACTIVE,
    DONE
  }
}
