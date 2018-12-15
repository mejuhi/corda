package net.corda.testing.node

import net.corda.core.DoNotImplement
import net.corda.core.internal.PLATFORM_VERSION
import net.corda.testing.node.internal.TestCordappImpl
import net.corda.testing.node.internal.simplifyScanPackages

/**
 * Represents information about a CorDapp. Used to generate CorDapp JARs in tests.
 */
@DoNotImplement
interface TestCordapp {
    /** Returns the name, defaults to "test-name" if not specified. */
    val name: String

    /** Returns the version string, defaults to 1 if not specified. */
    val versionId: Int

    /** Returns the target platform version, defaults to the current platform version if not specified. */
    val targetPlatformVersion: Int

    /** Returns the config for this CorDapp, defaults to empty if not specified. */
    val config: Map<String, Any>

    /** Returns the set of package names scanned for this test CorDapp. */
    val packages: Set<String>

    /** Return a copy of this [TestCordapp] but with the specified name. */
    fun withName(name: String): TestCordapp

    /** Return a copy of this [TestCordapp] but with the specified version ID. */
    fun withVersionId(versionId: Int): TestCordapp

    /** Return a copy of this [TestCordapp] but with the specified target platform version. */
    fun withTargetPlatformVersion(targetPlatformVersion: Int): TestCordapp

    /** Returns a copy of this [TestCordapp] but with the specified CorDapp config. */
    fun withConfig(config: Map<String, Any>): TestCordapp

    class Factory {
        companion object {
            /**
             * Create a [TestCordapp] object by scanning the given packages. The meta data on the CorDapp will be the
             * default values, which can be changed with the wither methods.
             */
            @JvmStatic
            fun fromPackages(vararg packageNames: String): TestCordapp = fromPackages(packageNames.asList())

            /**
             * Create a [TestCordapp] object by scanning the given packages. The meta data on the CorDapp will be the
             * default values, which can be changed with the wither methods.
             */
            @JvmStatic
            fun fromPackages(packageNames: Collection<String>): TestCordapp {
                return TestCordappImpl(
                        name = "test-name",
                        versionId = 1,
                        targetPlatformVersion = PLATFORM_VERSION,
                        config = emptyMap(),
                        packages = simplifyScanPackages(packageNames)
                )
            }
        }
    }
}
