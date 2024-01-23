package com.marcnuri.helm.jni;

import com.sun.jna.Native;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.ServiceLoader;

import static com.marcnuri.helm.jni.RemoteJarLoader.remoteJar;

public interface NativeLibrary {

  static NativeLibrary getInstance() {
    NativeLibrary nativeLibrary;
    ClassLoader remoteJar;
    if (
      // Load from ClassPath (should work on Maven)
      (nativeLibrary = serviceProviderLibrary(null)) != null ||
        // Load from remote JAR (should work on Gradle if not air-gapped)
        ((remoteJar = remoteJar()) != null && (nativeLibrary = serviceProviderLibrary(remoteJar)) != null)
    ) {
      return nativeLibrary;
    }
    throw new IllegalStateException("No NativeLibrary implementation found, please add one of the supported dependencies to your project");
  }

  static NativeLibrary serviceProviderLibrary(ClassLoader classLoader) {
    for (NativeLibrary nativeLibrary : ServiceLoader.load(
      NativeLibrary.class, classLoader == null ? Thread.currentThread().getContextClassLoader() : classLoader)
    ) {
      return nativeLibrary;
    }
    return null;
  }

  String getBinaryName();

  default HelmLib load() {
    final Path temp = createTempDirectory();
    final Path tempBinary = temp.resolve(getBinaryName());
    tempBinary.toFile().deleteOnExit();
    try (final InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream("/" + getBinaryName()))) {
      Files.copy(stream, tempBinary, StandardCopyOption.REPLACE_EXISTING);
      final HelmLib helmLib = Native.load(tempBinary.toAbsolutePath().toString(), HelmLib.class);
      // Cleanup any resources that might have been left behind
      Runtime.getRuntime().addShutdownHook(new Thread(helmLib::RepoServerStopAll));
      return helmLib;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load native library " + getBinaryName(), exception);
    }
  }

  default Path createTempDirectory() {
    try {
      final Path temp = Files.createTempDirectory("helm-java");
      temp.toFile().deleteOnExit();
      return temp;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to create temporary directory", exception);
    }
  }

}
