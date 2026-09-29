// Platform-neutral domain values. Depends on no other shared module (shared/AGENTS.md).
plugins { id("mconeworld.shared-module") }

dependencies { testImplementation(project(":shared:test-fixtures")) }
