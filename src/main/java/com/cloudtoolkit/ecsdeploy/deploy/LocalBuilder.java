package com.cloudtoolkit.ecsdeploy.deploy;

import com.intellij.openapi.project.Project;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs a local Maven / Gradle build before the deployment and resolves the produced artifact
 * (jar). Used by the "Maven Build" / "Gradle Build" file types of the ECS deploy configuration.
 */
public final class LocalBuilder {

    private static final int MAX_SEARCH_DEPTH = 5;

    private LocalBuilder() {
    }

    /**
     * Builds the project and returns the artifact that should be uploaded.
     *
     * @param buildType {@code maven} or {@code gradle}
     */
    public static Path build(Project project, String buildType, DeployObserver observer)
            throws DeployException {
        Path baseDir = project == null || project.getBasePath() == null
                ? null : Path.of(project.getBasePath());
        if (baseDir == null || !Files.isDirectory(baseDir)) {
            throw new DeployException("无法确定项目目录，无法执行本地构建");
        }
        boolean maven = "maven".equalsIgnoreCase(buildType);
        List<String> command = maven ? mavenCommand(baseDir) : gradleCommand(baseDir);
        if (command == null) {
            throw new DeployException(maven
                    ? "未找到 maven (mvnw.cmd / mvn)，无法执行 Maven 构建"
                    : "未找到 gradle (gradlew.bat / gradle)，无法执行 Gradle 构建");
        }

        observer.onOutput(false, "== " + String.join(" ", command) + " ==");
        int exitCode = run(command, baseDir, observer);
        if (exitCode != 0) {
            throw new DeployException("本地构建失败，退出码 " + exitCode);
        }
        Path artifact = findArtifact(baseDir, maven);
        if (artifact == null) {
            throw new DeployException(maven
                    ? "构建成功但未找到 target 目录下的 jar 产物"
                    : "构建成功但未找到 build/libs 目录下的 jar 产物");
        }
        observer.onOutput(false, "构建产物: " + artifact);
        return artifact;
    }

    private static List<String> mavenCommand(Path baseDir) {
        if (Files.exists(baseDir.resolve("mvnw.cmd"))) {
            return List.of(baseDir.resolve("mvnw.cmd").toString(), "clean", "package", "-DskipTests");
        }
        if (Files.exists(baseDir.resolve("mvnw"))) {
            return List.of(baseDir.resolve("mvnw").toString(), "clean", "package", "-DskipTests");
        }
        if (Files.exists(baseDir.resolve("pom.xml"))) {
            return List.of("mvn", "clean", "package", "-DskipTests");
        }
        return null;
    }

    private static List<String> gradleCommand(Path baseDir) {
        if (Files.exists(baseDir.resolve("gradlew.bat"))) {
            return List.of(baseDir.resolve("gradlew.bat").toString(), "build", "-x", "test");
        }
        if (Files.exists(baseDir.resolve("gradlew"))) {
            return List.of(baseDir.resolve("gradlew").toString(), "build", "-x", "test");
        }
        if (Files.exists(baseDir.resolve("build.gradle"))
                || Files.exists(baseDir.resolve("build.gradle.kts"))) {
            return List.of("gradle", "build", "-x", "test");
        }
        return null;
    }

    private static int run(List<String> command, Path workingDir, DeployObserver observer)
            throws DeployException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    observer.onOutput(false, line);
                }
            }
            return process.waitFor();
        } catch (IOException e) {
            throw new DeployException("无法执行本地构建: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeployException("本地构建被中断", e);
        }
    }

    /** Finds the most recently produced jar of the build. */
    static Path findArtifact(Path baseDir, boolean maven) throws DeployException {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(baseDir, MAX_SEARCH_DEPTH)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> isArtifact(path, maven))
                    .forEach(candidates::add);
        } catch (IOException e) {
            throw new DeployException("无法扫描构建产物: " + baseDir, e);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingLong(LocalBuilder::lastModified).reversed());
        return candidates.get(0);
    }

    private static boolean isArtifact(Path jar, boolean maven) {
        String name = jar.getFileName().toString().toLowerCase();
        if (name.contains("-sources") || name.contains("-javadoc") || name.contains("-tests")) {
            return false;
        }
        Path parent = jar.getParent();
        if (parent == null || parent.getFileName() == null) {
            return false;
        }
        String dir = parent.getFileName().toString();
        if (maven) {
            return "target".equals(dir);
        }
        return "libs".equals(dir) && parent.getParent() != null
                && "build".equals(parent.getParent().getFileName().toString());
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
