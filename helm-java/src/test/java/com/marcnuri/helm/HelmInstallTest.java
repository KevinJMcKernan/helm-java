package com.marcnuri.helm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelmInstallTest {

  @TempDir
  private Path tempDir;
  private Helm helm;

  @BeforeEach
  void setUp() {
    helm = Helm.create().withName("test").withDir(tempDir).call();
  }

  @Nested
  class Valid {

    @Test
    void withName() {
      final InstallResult result = helm.install()
        .clientOnly()
        .withName("test")
        .call();
      assertThat(result)
        .returns("test", InstallResult::getName)
        .returns("deployed", InstallResult::getStatus)
        .returns("1", InstallResult::getRevision)
        .extracting(InstallResult::getOutput).asString()
        .contains(
          "NAME: test\n",
          "LAST DEPLOYED: ",
          "STATUS: deployed",
          "REVISION: 1",
          "NOTES:"
        );
    }

    @Test
    void withPackagedChart(@TempDir Path destination) {
      helm.packageIt().withDestination(destination).call();
      final InstallResult result = Helm.install(destination.resolve("test-0.1.0.tgz").toFile().getAbsolutePath())
        .clientOnly()
        .withName("test")
        .call();
      assertThat(result)
        .hasFieldOrPropertyWithValue("name", "test");
    }

    @Test
    void withGenerateName() {
      final InstallResult result = helm.install()
        .clientOnly()
        .withName("test") // Should be ignored (omitted/not failure)
        .generateName()
        .call();
      assertThat(result)
        .hasFieldOrPropertyWithValue("status", "deployed")
        .extracting(InstallResult::getName).asString()
        .startsWith("test-");
    }

    @Test
    void withGenerateNameAndNameTemplate() {
      final InstallResult result = helm.install()
        .clientOnly()
        .generateName()
        .withNameTemplate("a-chart-{{randAlpha 6 | lower}}")
        .call();
      assertThat(result)
        .hasFieldOrPropertyWithValue("status", "deployed")
        .extracting(InstallResult::getName).asString()
        .startsWith("a-chart-");
    }

    @Test
    void withNamespace() {
      final InstallResult result = helm.install()
        .clientOnly()
        .withName("test")
        .withNamespace("test-namespace")
        .call();
      assertThat(result)
        .hasFieldOrPropertyWithValue("name", "test")
        .returns("test-namespace", InstallResult::getNamespace);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void withDependencyUpdate() throws IOException {
      Helm.create().withName("the-dependency").withDir(tempDir).call();
      Files.write(tempDir.resolve("test").resolve("Chart.yaml"),
        ("\ndependencies:\n" +
          "  - name: the-dependency\n" +
          "    version: 0.1.0\n" +
          "    repository: " + tempDir.resolve("the-dependency").toUri() +"\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND);
      final InstallResult result = helm.install()
        .clientOnly()
        .withName("dependency")
        .dependencyUpdate()
        .call();
      assertThat(result)
        .hasFieldOrPropertyWithValue("name", "dependency")
        .extracting(InstallResult::getOutput).asString()
        .contains(
          "Saving 1 charts",
          "Deleting outdated charts"
        );
      assertThat(tempDir.resolve("test").resolve("Chart.lock"))
        .exists()
        .isNotEmptyFile()
        .content().contains("name: the-dependency");
    }

    @Test
    void withDryRun() {
      final InstallResult result = helm.install()
        .clientOnly()
        .withName("test")
        .dryRun()
        .withDryRunOption(InstallCommand.DryRun.CLIENT)
        .call();
      assertThat(result)
        .hasFieldOrPropertyWithValue("name", "test")
        .hasFieldOrPropertyWithValue("status", "pending-install");
    }

    @Test
    void withValues() {
      final InstallResult result = helm.install()
        .clientOnly()
        .debug()
        .withName("test")
        .set("corner", "\"'\\={[,.]}!?-_test=1,other=2")
        .set("bool", "true")
        .set("int", "1")
        .set("float", "1.1")
        .call();
      assertThat(result)
        .extracting(InstallResult::getOutput).asString()
        .contains(
          "NAME: test\n",
          "USER-SUPPLIED VALUES:\n",
          "corner: '\"''\\={[,.]}!?-_test=1,other=2'\n",
          "bool: true\n",
          "int: 1\n",
          "float: \"1.1\"" // helm.sh/helm/v3/pkg/strvals does not support floats
        );
    }
  }

  @Nested
  class Invalid {

    @Test
    void withMissingChart() {
      final InstallCommand install = Helm.install(null).clientOnly().withName("test");
      assertThatThrownBy(install::call)
        .message()
        .containsAnyOf(
          "no such file or directory",
          "The system cannot find the path specified"
        );
    }

    @Test
    void withMissingName() {
      final InstallCommand install = helm.install().clientOnly();
      assertThatThrownBy(install::call)
        .hasMessage("release name \"\": no name provided");
    }

    @Test
    void withDependencyUpdateAndMissing() throws IOException {
      Files.write(tempDir.resolve("test").resolve("Chart.yaml"),
        ("\ndependencies:\n" +
        "  - name: dependency\n" +
        "    version: 0.1.0\n" +
        "    repository: file://i-dont-exist\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND);
      final InstallCommand install = helm.install().clientOnly().withName("dependency-missing").dependencyUpdate();
      assertThatThrownBy(install::call)
        .hasMessageContaining(
          "An error occurred while updating chart dependencies:",
          "i-dont-exist not found"
        );
    }

//    @Test
//    void withDevelopmentVersionInChart() throws IOException {
//      final Path chartYaml = tempDir.resolve("test").resolve("Chart.yaml");
//      final String chart = new String(Files.readAllBytes(chartYaml), StandardCharsets.UTF_8);
//      Files.write(chartYaml, chart.replace("version: 0.1.0", "version: 0.1.0-SNAPSHOT").getBytes(StandardCharsets.UTF_8));
//      final InstallCommand install = helm.install()
//        .clientOnly()
//        .withName("development-version");
//      assertThatThrownBy(install::call)
//        .hasMessage("release name \"\": no name provided");
//    }
  }


}