# Reconcile report, format `mconeworld-reconcile-report` 1.0 (normative)

- File: `mconeworld-data/reports/reconcile-<operationId>.jsonl`, one per reconcile that removed records or bound the world. The sidecar manifest references it in `files`, with role `reconcile-report`, checksum, and size.
- Written by: MCOneWorld Tool's reconcile (Tool T6, `tool.core.reconcile.Reconciler`), **before** any record is removed. Removal never loses history without a trace (root AGENTS.md §2).

## Content

JSON Lines (UTF-8, one compact JSON object per line, each followed by LF).

1. **Header** (first line), with members in this order:

   ```json
   {"format":"mconeworld-reconcile-report","operation":"<uuid>","removed":<n>,"version":"1.0"}
   ```

   - `operation`: the reconcile's operation id
   - `removed`: the number of record lines that follow
2. **One line per removed record.** The record exactly as the index held it, in the canonical record line of the chunk-index export ([`chunk-index-v1.md`](chunk-index-v1.md) §10). The lines are ordered by dimension, then `ChunkOrder`.

## Uses

- **Audit.** It shows which chunk histories were dropped because the world no longer has those chunks.
- **Re-adding.** A record can be re-added to an index from its line, through `ChunkIndexExport.read`'s record syntax.
- **Determinism.** Given the same plan and the same index, the bytes are identical; a resumed reconcile reproduces them exactly (tested).
