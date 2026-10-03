// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.backup;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The regular files below a directory, without following symbolic links, sorted by path in
 * code-point order. A symbolic link is reported, never followed.
 *
 * @param root the directory
 * @param files every regular file, relative path with {@code /} separators
 * @param issues symbolic links and other unsupported entries found
 */
record FileTree(Path root, List<Entry> files, List<ValidationIssue> issues) {

  /**
   * One file.
   *
   * @param path relative to the root, {@code /}-separated
   * @param size its size
   * @param modifiedMillis its last-modified time
   */
  record Entry(String path, long size, long modifiedMillis) {}

  FileTree {
    files = List.copyOf(files);
    issues = List.copyOf(issues);
  }

  /**
   * Walks {@code root}, skipping relative paths {@code excluded} accepts (a directory skips its
   * subtree).
   */
  static FileTree walk(Path root, Predicate<String> excluded) throws IOException {
    List<Entry> files = new ArrayList<>();
    List<ValidationIssue> issues = new ArrayList<>();
    walk(root, "", excluded, files, issues);
    files.sort(Comparator.comparing(Entry::path, FileTree::compareCodePoints));
    return new FileTree(root, files, issues);
  }

  private static void walk(
      Path dir,
      String prefix,
      Predicate<String> excluded,
      List<Entry> files,
      List<ValidationIssue> issues)
      throws IOException {
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
      for (Path child : children) {
        String relative = prefix + child.getFileName();
        if (excluded.test(relative)) {
          continue;
        }
        BasicFileAttributes attributes =
            Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
          issues.add(
              ValidationIssue.error(
                      BackupIssueCodes.UNSUPPORTED_FILE,
                      IssueLocation.root().key(relative),
                      (attributes.isSymbolicLink() ? "Symbolic link" : "Special file")
                          + " not backed up: "
                          + relative)
                  .withRemediation("Replace it with a regular file or directory, or remove it."));
        } else if (attributes.isDirectory()) {
          walk(child, relative + "/", excluded, files, issues);
        } else {
          files.add(
              new Entry(relative, attributes.size(), attributes.lastModifiedTime().toMillis()));
        }
      }
    }
  }

  /** Total size of every file. */
  long totalBytes() {
    long total = 0;
    for (Entry e : files) {
      total = Math.addExact(total, e.size());
    }
    return total;
  }

  /** A digest of every path, size, and modification time: equal digests mean an unchanged tree. */
  Checksum fingerprint() {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    for (Entry e : files) {
      digest.update(
          (e.path() + "\t" + e.size() + "\t" + e.modifiedMillis() + "\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    return Checksum.ofDigest(Checksum.Algorithm.SHA_256, digest.digest());
  }

  static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i);
      int cb = b.codePointAt(j);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }
}
