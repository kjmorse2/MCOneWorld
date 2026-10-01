# shared:domain

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-domain` · Package: `io.github.kjmorse2.mconeworld.domain`

**Responsibility.** Platform-neutral, validated domain values shared by at least two products:

- identifiers
- dimensions
- block, chunk, region, and season-local coordinates
- seasons and chunk provenance
- operation state
- structured validation results

**May depend on:** JDK and JSpecify annotations only.

**Forbidden:**

- other shared modules
- serialization, compression, or database libraries
- Paper, Bukkit, Fabric, Minecraft, Adventure, JavaFX, MCA Selector (`net.querz`), and mappings

`verifySharedDependencies` and `verifySharedBytecode` enforce these rules.

**Status:** identifiers and coordinates are done (Prompt 02), as are seasons, provenance, archive state, and validation (Prompt 03), and operation plans and state (Prompt 07).

Packages:

- `domain`: `SeasonId`, `OperationId`, `DimensionKey`, `DomainErrorCode`, `InvalidDomainValueException`
- `domain.coordinate`: `BlockPosition`, `ChunkPosition`, `RegionPosition`, `ChunkRectangle`, `DimensionOrigin`, `SeasonLocalPosition`, `OverworldNetherScaling`, `OriginAlignment`, `WorldBounds`, `CoordinateLimits`
- `domain` (Prompt 03 additions): `BackupId`, `PortalMarkerId`, `ProtectedRegionId`, `PlayerArchiveId`, `PlayerId`, `DisplayName`, `Checksum`
- `domain.validation`: `ValidationIssue`, `ValidationResult`, `IssueCode`, `IssueLocation`, `Severity`, `ValidationException`
- `domain.season`: `SeasonRecord`, `SeasonStatus`
- `domain.provenance`: `ChunkProvenance`, `FirstObservation` (`InSeason` or `Legacy`), `SeasonActivity`, `ProvenanceEvidence`, `EvidenceSource`, `Confidence`, `ChunkProtection`, `ChunkEnvelope`, `ProtectedRegion`
- `domain` (Prompt 07 addition): `TextRules`, the shared rules for bounded display text
- `domain.operation`:
  - plan: `OperationPlan`, `OperationType`, `ActionKey`, `InputKey`, `OperationText`, `ToolIdentity`, `ImpactClass`, `OperationTarget`, `PreconditionSnapshot`, `ExpectedManifest`, `BackupRequirement`, `BackupReference`, `StepDescriptor`, `ValidationDescriptor`, `RollbackDeclaration`, `RollbackStrategy`
  - state: `OperationState`, `JournalEventType`, `JournalEvent`, `JournalEntry`, `FailureReason`, `OperationTransitions`, `OperationProgress`, `OperationIssueCodes`
  - recovery: `RecoveryRecommendation`, `RecoveryAction`

  The format is in [`docs/formats/operation-journal-v1.md`](../../docs/formats/operation-journal-v1.md); the policy is in ADR 0006.
- `domain.archive`: `ArchiveState`, `ArchiveIssueCodes`, `PortalMarker`, `BackupRecord`, `PlayerArchiveRecord`, `ArchiveCategory`

See [`../README.md`](../README.md#public-api) for the API summary.
