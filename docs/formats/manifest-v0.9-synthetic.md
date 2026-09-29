# Manifest schema 0.9: SYNTHETIC TEST FIXTURE (never released)

> **This format was never released and is not supported.** It exists only to exercise the migration framework (Prompt 06) before any real legacy format exists. No production registry contains a 0.9 migration: `MigrationRegistry.builtIn()` is empty. The fixture and its migration live in test sources:
>
> - `shared/sidecar/src/test/resources/.../migration/v0.9-synthetic.json`
> - `SyntheticManifest09To10` in `shared/sidecar/src/test/java/.../migration/`

It is deliberately small, and it differs from 1.0 in ways that cover each kind of migration work:

| 0.9 member | 1.0 equivalent | Kind of change |
|---|---|---|
| `archive` (uuid) | `archiveId` | rename |
| `created` (epoch milliseconds, integer) | `createdAt` (ISO-8601 UTC) | type conversion |
| `active` (uuid, **absent** when none) | `activeSeason` (uuid or `null`) | absent → explicit null |
| `seasons[].created` | `seasons[].createdAt` | rename and conversion |
| `seasons[].state` (`planned`/`active`/`archived`/`cancelled`) | `seasons[].status` (upper case) | enum mapping |
| `seasons[].origins` (array of `{dimension,x,y,z}`) | `seasons[].origins` (object keyed by dimension) | restructure. A repeated dimension is **unsupported** and cannot be represented. |
| `portals[]` (`id`, `label`, `destination`, `created`) | `portalMarkers[]`, with `destinationSeason: null` | rename and added default |
| `notes` (optional free text) | `extensions["mconeworld:legacy_notes"]` | moved losslessly into a documented extension point |
| `extensions` (optional object with namespaced keys) | `extensions` | **preserved** |
| `experimental` (optional object) | none | **unsupported** if non-empty: the migration refuses rather than dropping it |
| none | `protectedRegions`, `backups`, `playerArchives`, `files`, `features` | added empty |
| `revision` | `revision` + 1 | the migration is a committed change |
| any other member | none | **prerequisite failure**: unknown data is never guessed at or dropped |
