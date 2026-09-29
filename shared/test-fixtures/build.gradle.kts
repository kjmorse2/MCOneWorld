// Test support only. Production configurations of other shared modules may never depend on this
// project; verifySharedDependencies in each module enforces that.
plugins { id("mconeworld.shared-module") }

dependencies { api(project(":shared:domain")) }
