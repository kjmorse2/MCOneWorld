// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.sidecar.storage.IndexFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageIssueCodes;
import java.io.IOException;
import java.nio.file.NoSuchFileException;

/**
 * Reads the committed state: the catalog, then the log of its generation. Read-only.
 *
 * <p>Another process's writer may checkpoint between those two reads, replacing the catalog and
 * deleting the old log. The loader detects that (the log vanished, or the catalog's generation
 * moved) and retries, so a reader in another process always gets one consistent committed state.
 */
final class IndexLoader {
  private static final int ATTEMPTS = 5;

  private IndexLoader() {}

  static IndexSnapshot load(IndexFiles files, ChunkIndexOptions options)
      throws ChunkIndexException {
    try {
      if (!files.areaExists() || !files.exists(IndexFormat.CATALOG)) {
        throw new ChunkIndexException(
            ChunkIndexIssueCodes.INDEX_MISSING, "there is no chunk index; create one explicitly");
      }
    } catch (ChunkIndexException e) {
      throw e;
    } catch (StorageException e) {
      throw new ChunkIndexException(e.code(), String.valueOf(e.getMessage()), "chunk-index", e);
    }
    ChunkIndexException last = null;
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      byte[] catalogBytes = read(files, IndexFormat.CATALOG, options.maxCatalogBytes());
      Catalog catalog = Catalog.decode(catalogBytes);
      int catalogCrc =
          new ByteSource(
                  catalogBytes,
                  catalogBytes.length - 4,
                  4,
                  ChunkIndexIssueCodes.CATALOG_CORRUPT,
                  "catalog")
              .i32();
      SidecarPath logPath = IndexFormat.logPath(catalog.generation);
      DeltaLog.Replay replay = null;
      boolean logVanished = false;
      try {
        if (files.exists(logPath)) {
          byte[] logBytes = read(files, logPath, options.maxLogBytes());
          replay =
              DeltaLog.replay(
                  logBytes,
                  logPath.toString(),
                  Integer.MAX_VALUE,
                  catalog.indexId,
                  catalog.generation);
          IndexFormat.requireIndex(
              new IndexFormat.FileHeader(0, replay.indexId(), replay.generation()),
              catalog.indexId,
              logPath.toString());
          if (replay.generation() != catalog.generation) {
            throw new ChunkIndexException(
                ChunkIndexIssueCodes.HEADER_CORRUPT,
                "log header names generation " + replay.generation(),
                logPath.toString(),
                null);
          }
        }
      } catch (ChunkIndexException e) {
        if (!(e.getCause() instanceof NoSuchFileException)) {
          throw e;
        }
        logVanished = true;
        last = e;
      } catch (StorageException e) {
        throw new ChunkIndexException(
            e.code(), String.valueOf(e.getMessage()), logPath.toString(), e);
      }
      if (logVanished || currentGeneration(files) != catalog.generation) {
        continue; // a checkpoint completed meanwhile; read again
      }
      if (replay == null) {
        return new IndexSnapshot(
            catalog,
            Overlay.EMPTY,
            IndexFormat.HEADER_BYTES,
            false,
            false,
            catalog.newerMinor,
            catalogCrc,
            0);
      }
      return new IndexSnapshot(
          catalog,
          replay.overlay(),
          replay.validEnd(),
          replay.tornTail(),
          true,
          catalog.newerMinor || replay.newerMinor(),
          catalogCrc,
          replay.tailCrc());
    }
    throw new ChunkIndexException(
        ChunkIndexIssueCodes.IO_FAILURE,
        "the index kept changing while it was being read; try again",
        "chunk-index",
        last);
  }

  /** The catalog's current generation, from its header only. */
  static long currentGeneration(IndexFiles files) throws ChunkIndexException {
    byte[] head = new byte[IndexFormat.HEADER_BYTES];
    try (StorageFileSystem.ReadableFile file = files.openRandomAccess(IndexFormat.CATALOG)) {
      file.readFully(0, head, 0, head.length);
    } catch (IOException e) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.IO_FAILURE, "cannot read the catalog header", "catalog", e);
    }
    return IndexFormat.readHeader(head, 0, head.length, IndexFormat.CATALOG_MAGIC, "catalog")
        .number();
  }

  /** Reads a whole file, mapping storage failures to index codes. */
  static byte[] read(IndexFiles files, SidecarPath path, long maxBytes) throws ChunkIndexException {
    try {
      return files.read(path, maxBytes);
    } catch (NoSuchFileException e) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.IO_FAILURE, "file vanished while reading", path.toString(), e);
    } catch (StorageException e) {
      if (e.code().equals(StorageIssueCodes.TOO_LARGE)) {
        throw new ChunkIndexException(
            ChunkIndexIssueCodes.TOO_LARGE,
            "file exceeds its configured limit",
            path.toString(),
            e);
      }
      throw new ChunkIndexException(e.code(), String.valueOf(e.getMessage()), path.toString(), e);
    } catch (IOException e) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.IO_FAILURE, "cannot read", path.toString(), e);
    }
  }
}
