package org.opengis.tool.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemSkillRepositoryTest {
  @TempDir Path workspace;

  @Test
  void discoversAllWorkspaceRootsAndLoadsTheHighestPrecedenceMatch() throws Exception {
    write(
        workspace.resolve(".agents/skills/review/SKILL.md"),
        "---\nname: review\ndescription: Agent review\n---\nAgent instructions");
    write(
        workspace.resolve("skills/review/SKILL.md"),
        "---\nname: review\ndescription: Compatibility review\n---\nCompatibility");
    write(
        workspace.resolve(".opengis/skills/review/SKILL.md"),
        "---\nname: review\ndescription: Workspace review\ntags: [gis, review]\n---\n"
            + "Workspace instructions");
    write(
        workspace.resolve(".opengis/skills/build/SKILL.md"),
        "---\nname: build\ndescription: Build data\n---\nBuild instructions");

    FileSystemSkillRepository repository = new FileSystemSkillRepository();

    assertThat(repository.discover(workspace))
        .extracting(SkillDescriptor::name)
        .contains("build", "review");
    LoadedSkill loaded = repository.load(workspace, "review").orElseThrow();
    assertThat(loaded.descriptor().description()).isEqualTo("Workspace review");
    assertThat(loaded.descriptor().tags()).containsExactly("gis", "review");
    assertThat(loaded.content()).contains("Workspace instructions");
  }

  @Test
  void keepsGlobalSkillsAcrossWorkspacesAndAllowsProjectOverrides() throws Exception {
    Path global = workspace.resolve("global-skills");
    Path project = workspace.resolve("project");
    write(
        global.resolve("gis-review/SKILL.md"),
        "---\nname: gis-review\ndescription: Global default\n---\nGlobal instructions");
    write(
        global.resolve("shared/SKILL.md"),
        "---\nname: shared\ndescription: Shared skill\n---\nShared instructions");
    write(
        project.resolve(".opengis/skills/gis-review/SKILL.md"),
        "---\nname: gis-review\ndescription: Project override\n---\nProject instructions");

    FileSystemSkillRepository repository =
        new FileSystemSkillRepository(
            global, List.of(), FileSystemSkillRepository.DEFAULT_MAX_SKILL_BYTES);

    assertThat(repository.discover(null))
        .extracting(SkillDescriptor::name)
        .containsExactly("gis-review", "shared");
    assertThat(repository.load(project, "gis-review").orElseThrow().content())
        .contains("Project instructions");
    assertThat(repository.load(project, "shared").orElseThrow().content())
        .contains("Shared instructions");
  }

  @Test
  void rejectsInvalidNamesAndIgnoresOversizedSkills() throws Exception {
    write(workspace.resolve("skills/large/SKILL.md"), "x".repeat(2048));
    FileSystemSkillRepository repository = new FileSystemSkillRepository(1024);

    assertThat(repository.discover(workspace))
        .extracting(SkillDescriptor::name)
        .doesNotContain("large");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.load(workspace, "../escape"))
        .isInstanceOf(SkillRepositoryException.class)
        .hasMessage("Invalid skill name");
  }

  @Test
  void listsAndReadsTextResourcesWithoutEscapingTheSkillPackage() throws Exception {
    Path skill = workspace.resolve(".opengis/skills/gis-review");
    write(
        skill.resolve("SKILL.md"),
        "---\nname: gis-review\ndescription: Review GIS\n---\nRead references/checks.md");
    write(skill.resolve("references/checks.md"), "Check CRS and geometry.");
    write(skill.resolve("scripts/validate.py"), "print('validate')");
    write(skill.resolve("assets/diagram.png"), "not loaded as text");
    write(workspace.resolve("outside.md"), "outside");

    FileSystemSkillRepository repository = new FileSystemSkillRepository();

    assertThat(repository.listResources(workspace, "gis-review"))
        .extracting(SkillResourceDescriptor::path)
        .containsExactly("assets/diagram.png", "references/checks.md", "scripts/validate.py");
    assertThat(repository.listResources(workspace, "gis-review"))
        .filteredOn(resource -> resource.path().equals("assets/diagram.png"))
        .extracting(SkillResourceDescriptor::readable)
        .containsExactly(false);
    assertThat(
            repository
                .readResource(workspace, "gis-review", "references/checks.md")
                .orElseThrow()
                .content())
        .contains("Check CRS");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.readResource(workspace, "gis-review", "../../outside.md"))
        .isInstanceOf(SkillRepositoryException.class)
        .hasMessage("Skill resource escapes its package");
  }

  @Test
  void readsLargeTextResourcesInConfigurableSlices() throws Exception {
    Path skill = workspace.resolve(".opengis/skills/paged");
    write(skill.resolve("SKILL.md"), "---\nname: paged\n---\nRead references/large.md");
    write(skill.resolve("references/large.md"), "abcdefghijkl");
    SkillRepositorySettings settings = new SkillRepositorySettings(1024, 4096, 5, 10, 20);
    FileSystemSkillRepository repository =
        new FileSystemSkillRepository(workspace.resolve("global"), List.of(), settings);

    LoadedSkillResource first =
        repository.readResource(workspace, "paged", "references/large.md", 0, 100).orElseThrow();
    LoadedSkillResource second =
        repository
            .readResource(workspace, "paged", "references/large.md", first.nextOffset(), 5)
            .orElseThrow();

    assertThat(first.content()).isEqualTo("abcde");
    assertThat(first.nextOffset()).isEqualTo(5);
    assertThat(first.totalChars()).isEqualTo(12);
    assertThat(first.truncated()).isTrue();
    assertThat(second.content()).isEqualTo("fghij");
  }

  private static void write(Path path, String content) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }
}
