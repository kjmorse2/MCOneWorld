# Notices and attribution

MCOneWorld is released under the MIT License. See [`LICENSE`](LICENSE), Copyright (c) 2026 Kenenth Morse. Redistributions must retain that copyright notice.

## Third-party code in this repository

| Path | Project | License | Copyright |
|---|---|---|---|
| `offline-tool/` | [MCA Selector](https://github.com/Querz/mcaselector), imported as a `git subtree` | MIT (see [`offline-tool/LICENSE`](offline-tool/LICENSE)) | (c) 2018–2026 Querz |
| `tool/world/…/layout/LayoutDetector.java` | Adapted from MCA Selector's `FileHelper.detectDimensionDirectories` (upstream `d31bee6a`; ADR 0009). The file header says so. | MIT | (c) 2018–2026 Querz |

The MCA Selector license and its bundled third-party license texts (`offline-tool/src/main/resources/licenses/`) must ship with any distribution of MCOneWorld Tool that contains MCA Selector code. Known gaps to close before the first Tool release: add license entries for Querz NBT (MIT), slf4j-simple (MIT), and JavaFX (GPL-2.0 with Classpath Exception). See `docs/architecture/shared-project-plan.md` §2.

Third-party libraries used by the shared modules, with their licenses, are listed in `docs/compatibility/version-matrix.md`. `scripts/dependency-report.py` lists the license of every locked artifact. The shared jars embed only MCOneWorld's own `META-INF/LICENSE` and bundle no third-party classes. A platform jar that shades `jackson-core` (Apache-2.0) must also carry jackson's `LICENSE` and `NOTICE` files.
