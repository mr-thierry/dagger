/*
 * Copyright (C) 2020 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.testing.compile;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.collect.Streams.stream;
import static com.google.testing.compile.Compiler.javac;

import androidx.room.compiler.processing.XProcessingEnvConfig;
import androidx.room.compiler.processing.util.CompilationResultSubject;
import androidx.room.compiler.processing.util.ProcessorTestExtKt;
import androidx.room.compiler.processing.util.Source;
import androidx.room.compiler.processing.util.compiler.TestCompilationArguments;
import androidx.room.compiler.processing.util.compiler.TestCompilationResult;
import androidx.room.compiler.processing.util.compiler.TestKotlinCompilerKt;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.testing.compile.Compiler;
import dagger.internal.codegen.ComponentProcessor;
import dagger.internal.codegen.KspComponentProcessor;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import org.junit.rules.TemporaryFolder;

/** A helper class for working with java compiler tests. */
public final class CompilerTests {
  // TODO(bcorso): Share this with java/dagger/internal/codegen/DelegateComponentProcessor.java
  private static final XProcessingEnvConfig PROCESSING_ENV_CONFIG =
      new XProcessingEnvConfig.Builder().disableAnnotatedElementValidation(true).build();

  // TODO(bcorso): Share this with javatests/dagger/internal/codegen/Compilers.java
  private static final ImmutableMap<String, String> DEFAULT_PROCESSOR_OPTIONS =
      ImmutableMap.of(
          "dagger.experimentalDaggerErrorMessages", "enabled");

  /** Returns a {@link Source.KotlinSource} with the given file name and content. */
  public static Source kotlinSource(String fileName, String... srcLines) {
    return Source.Companion.kotlin(fileName, String.join("\n", srcLines));
  }

  /** Returns a {@link Source.JavaSource} with the given file name and content. */
  public static Source javaSource(String fileName, String... srcLines) {
    return Source.Companion.java(fileName, String.join("\n", srcLines));
  }

  /** Returns a {@link Compiler} instance with the given sources. */
  public static DaggerCompiler daggerCompiler(Source... sources) {
    return daggerCompiler(ImmutableList.copyOf(sources));
  }

  /** Returns a {@link Compiler} instance with the given sources. */
  public static DaggerCompiler daggerCompiler(ImmutableCollection<Source> sources) {
    return DaggerCompiler.builder().sources(sources).build();
  }

  /** Used to compile Dagger sources and inspect the compiled results. */
  @AutoValue
  public abstract static class DaggerCompiler {
    static Builder builder() {
      Builder builder = new AutoValue_CompilerTests_DaggerCompiler.Builder();
      // Set default values
      return builder.processorOptions(DEFAULT_PROCESSOR_OPTIONS);
    }

    /** Returns the sources being compiled */
    abstract ImmutableCollection<Source> sources();

    /** Returns the annotation processor options */
    abstract ImmutableMap<String, String> processorOptions();

    /** Returns a builder with the current values of this {@link Compiler} as default. */
    abstract Builder toBuilder();

    /**
     * Returns a new {@link Compiler} instance with the given processor options.
     *
     * <p>Note that the default processor options are still applied unless they are explicitly
     * overridden by the given processing options.
     */
    public DaggerCompiler withProcessingOptions(Map<String, String> processorOptions) {
      // Add default processor options first to allow overridding with new key-value pairs.
      Map<String, String> newProcessorOptions = new HashMap<>(DEFAULT_PROCESSOR_OPTIONS);
      newProcessorOptions.putAll(processorOptions);
      return toBuilder().processorOptions(newProcessorOptions).build();
    }

    public void compile(Consumer<CompilationResultSubject> onCompilationResult) {
      ProcessorTestExtKt.runProcessorTest(
          sources().asList(),
          /* classpath= */ ImmutableList.of(),
          processorOptions(),
          /* javacArguments= */ ImmutableList.of(),
          /* kotlincArguments= */ ImmutableList.of(),
          /* config= */ PROCESSING_ENV_CONFIG,
          /* javacProcessors= */ ImmutableList.of(new ComponentProcessor()),
          /* symbolProcessorProviders= */ ImmutableList.of(new KspComponentProcessor.Provider()),
          result -> {
            onCompilationResult.accept(result);
            return null;
          });
    }

    /** Used to build a {@link DaggerCompiler}. */
    @AutoValue.Builder
    public abstract static class Builder {
      abstract Builder sources(ImmutableCollection<Source> sources);
      abstract Builder processorOptions(Map<String, String> processorOptions);
      abstract DaggerCompiler build();
    }
  }

  /** Returns the {@plainlink File jar file} containing the compiler deps. */
  public static File compilerDepsJar() {
    try {
      return stream(Files.fileTraverser().breadthFirst(getRunfilesDir()))
          .filter(file -> file.getName().endsWith("_compiler_deps_deploy.jar"))
          .collect(onlyElement());
    } catch (NoSuchElementException e) {
      throw new IllegalStateException(
          "No compiler deps jar found. Are you using the Dagger compiler_test macro?", e);
    }
  }

  /** Returns a {@link Compiler} with the compiler deps jar added to the class path. */
  public static Compiler compiler() {
    return javac().withClasspath(ImmutableList.of(compilerDepsJar()));
  }

  public static void compileWithKapt(
      List<Source> sources,
      TemporaryFolder tempFolder,
      Consumer<TestCompilationResult> onCompilationResult) {
    compileWithKapt(sources, ImmutableMap.of(), tempFolder, onCompilationResult);
  }

  public static void compileWithKapt(
      List<Source> sources,
      Map<String, String> processorOptions,
      TemporaryFolder tempFolder,
      Consumer<TestCompilationResult> onCompilationResult) {
    TestCompilationResult result = TestKotlinCompilerKt.compile(
        tempFolder.getRoot(),
        new TestCompilationArguments(
            sources,
            /*classpath=*/ ImmutableList.of(compilerDepsJar()),
            /*inheritClasspath=*/ false,
            /*javacArguments=*/ ImmutableList.of(),
            /*kotlincArguments=*/ ImmutableList.of(),
            /*kaptProcessors=*/ ImmutableList.of(new ComponentProcessor()),
            /*symbolProcessorProviders=*/ ImmutableList.of(),
            /*processorOptions=*/ processorOptions));
    onCompilationResult.accept(result);
  }

  private static File getRunfilesDir() {
    return getRunfilesPath().toFile();
  }

  private static Path getRunfilesPath() {
    Path propPath = getRunfilesPath(System.getProperties());
    if (propPath != null) {
      return propPath;
    }

    Path envPath = getRunfilesPath(System.getenv());
    if (envPath != null) {
      return envPath;
    }

    Path cwd = Paths.get("").toAbsolutePath();
    return cwd.getParent();
  }

  private static Path getRunfilesPath(Map<?, ?> map) {
    String runfilesPath = (String) map.get("TEST_SRCDIR");
    return isNullOrEmpty(runfilesPath) ? null : Paths.get(runfilesPath);
  }

  private CompilerTests() {}
}
