// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NullMarked;

/**
 * Build-contract assertions every shared module, and every MCOneWorld Tool module, verifies in its
 * smoke test.
 *
 * <p>Two properties are checked because consumers silently depend on them:
 *
 * <ul>
 *   <li>The package is {@code @NullMarked}, so NullAway actually analyses it and consumers see
 *       JSpecify nullness.
 *   <li>The package was compiled to Java 21 bytecode, so the Java 21 offline tool can load it (ADR
 *       0002). A toolchain or {@code --release} change that breaks this fails the smoke test.
 * </ul>
 */
public final class ModuleContract {
  /** Class-file major version emitted for {@code --release 21} (JVMS §4.1). */
  public static final int JAVA_21_CLASS_FILE_MAJOR = 65;

  /** Class-file major version emitted for {@code --release 25}: the Tool's modules (ADR 0009). */
  public static final int JAVA_25_CLASS_FILE_MAJOR = 69;

  private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

  private ModuleContract() {}

  /**
   * Asserts that {@code packageName} has a {@code package-info} annotated {@code @NullMarked} and
   * compiled to Java 21 bytecode.
   *
   * @throws AssertionError if either property does not hold or the package has no package-info
   */
  public static void assertSharedPackageContract(String packageName) {
    assertPackageContract(packageName, JAVA_21_CLASS_FILE_MAJOR);
  }

  /**
   * Asserts that {@code packageName} has a {@code package-info} annotated {@code @NullMarked} and
   * compiled to class-file major version {@code expectedMajor}.
   *
   * @throws AssertionError if either property does not hold or the package has no package-info
   */
  public static void assertPackageContract(String packageName, int expectedMajor) {
    Class<?> packageInfo;
    try {
      packageInfo = Class.forName(packageName + ".package-info");
    } catch (ClassNotFoundException e) {
      throw new AssertionError("Package " + packageName + " has no package-info", e);
    }
    if (!packageInfo.getPackage().isAnnotationPresent(NullMarked.class)) {
      throw new AssertionError("Package " + packageName + " is not @NullMarked");
    }
    int major = classFileMajorVersion(readClassFile(packageInfo));
    if (major != expectedMajor) {
      throw new AssertionError(
          "Package "
              + packageName
              + " compiled to class-file major "
              + major
              + ", expected "
              + expectedMajor);
    }
  }

  /**
   * Returns the class-file major version from a class file's header.
   *
   * @throws IllegalArgumentException if {@code classFile} is not a class file
   */
  public static int classFileMajorVersion(byte[] classFile) {
    if (classFile.length < 8) {
      throw new IllegalArgumentException("Too short to be a class file: " + classFile.length);
    }
    int magic =
        ((classFile[0] & 0xFF) << 24)
            | ((classFile[1] & 0xFF) << 16)
            | ((classFile[2] & 0xFF) << 8)
            | (classFile[3] & 0xFF);
    if (magic != CLASS_FILE_MAGIC) {
      throw new IllegalArgumentException("Missing 0xCAFEBABE class-file magic");
    }
    return ((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF);
  }

  private static byte[] readClassFile(Class<?> type) {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getResourceAsStream(resource)) {
      if (in == null) {
        throw new AssertionError("Class file not found: " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new AssertionError("Cannot read " + resource, e);
    }
  }
}
