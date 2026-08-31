/** 文件职责：framework 后端领域：验证对应功能的行为与边界。 */
package org.opengis.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginRuntimeTest {
  @Test
  void mountsDependenciesFirstAndDisposesInReverseOrder() {
    List<String> lifecycle = new ArrayList<>();
    OpenGisPlugin storage = plugin("storage", Set.of(), lifecycle);
    OpenGisPlugin tools = plugin("tools", Set.of("storage"), lifecycle);

    PluginRuntime runtime =
        new PluginRuntime(PluginContext.builder().build(), List.of(tools, storage)).start();

    assertThat(runtime.mountedPluginIds()).containsExactly("storage", "tools");
    runtime.close();
    runtime.close();
    assertThat(lifecycle)
        .containsExactly("mount:storage", "mount:tools", "close:tools", "close:storage");
  }

  @Test
  void profileIncludesTransitiveDependenciesOnly() {
    List<String> lifecycle = new ArrayList<>();
    PluginRuntime runtime =
        new PluginRuntime(
                PluginContext.builder().build(),
                List.of(
                    plugin("unused", Set.of(), lifecycle),
                    plugin("ui", Set.of("tools"), lifecycle),
                    plugin("tools", Set.of("storage"), lifecycle),
                    plugin("storage", Set.of(), lifecycle)),
                new PluginProfile("desktop", Set.of("ui")))
            .start();

    assertThat(runtime.mountedPluginIds()).containsExactly("storage", "tools", "ui");
    runtime.close();
  }

  @Test
  void rejectsMissingDependenciesAndCycles() {
    PluginContext context = PluginContext.builder().build();
    assertThatThrownBy(
            () ->
                new PluginRuntime(
                        context, List.of(plugin("tools", Set.of("missing"), new ArrayList<>())))
                    .start())
        .isInstanceOf(PluginLifecycleException.class)
        .hasMessageContaining("Unknown plugin missing");

    assertThatThrownBy(
            () ->
                new PluginRuntime(
                        context,
                        List.of(
                            plugin("first", Set.of("second"), new ArrayList<>()),
                            plugin("second", Set.of("first"), new ArrayList<>())))
                    .start())
        .isInstanceOf(PluginLifecycleException.class)
        .hasMessageContaining("first -> second -> first");
  }

  @Test
  void rollsBackPreviouslyMountedPluginsWhenMountFails() {
    List<String> lifecycle = new ArrayList<>();
    OpenGisPlugin first = plugin("first", Set.of(), lifecycle);
    OpenGisPlugin broken =
        new OpenGisPlugin() {
          @Override
          public PluginDescriptor descriptor() {
            return new PluginDescriptor("broken", "1.0.0", Set.of("first"));
          }

          @Override
          public PluginHandle mount(PluginContext context) {
            lifecycle.add("mount:broken");
            throw new IllegalStateException("boom");
          }
        };

    assertThatThrownBy(
            () ->
                new PluginRuntime(PluginContext.builder().build(), List.of(broken, first)).start())
        .isInstanceOf(PluginLifecycleException.class)
        .hasRootCauseMessage("boom");
    assertThat(lifecycle).containsExactly("mount:first", "mount:broken", "close:first");
  }

  @Test
  void providesTypedHostServices() {
    AtomicInteger value = new AtomicInteger(7);
    PluginContext context = PluginContext.builder().service(AtomicInteger.class, value).build();

    assertThat(context.require(AtomicInteger.class)).isSameAs(value);
    assertThat(context.find(String.class)).isEmpty();
    assertThatThrownBy(() -> context.require(String.class))
        .isInstanceOf(PluginLifecycleException.class)
        .hasMessageContaining(String.class.getName());
  }

  private static OpenGisPlugin plugin(String id, Set<String> dependencies, List<String> lifecycle) {
    return new OpenGisPlugin() {
      @Override
      public PluginDescriptor descriptor() {
        return new PluginDescriptor(id, "1.0.0", dependencies);
      }

      @Override
      public PluginHandle mount(PluginContext context) {
        lifecycle.add("mount:" + id);
        return () -> lifecycle.add("close:" + id);
      }
    };
  }
}
