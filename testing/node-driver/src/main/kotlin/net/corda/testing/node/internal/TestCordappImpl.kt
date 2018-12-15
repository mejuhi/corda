package net.corda.testing.node.internal

import net.corda.testing.node.TestCordapp
import java.nio.file.Path

data class TestCordappImpl(override val name: String,
                           override val versionId: Int,
                           override val targetPlatformVersion: Int,
                           override val config: Map<String, Any>,
                           override val packages: Set<String>,
                           val signJar: Boolean = false,
                           val keyStorePath: Path? = null,
                           val classes: Set<Class<*>> = emptySet()
) : TestCordapp {

    override fun withName(name: String): TestCordappImpl = copy(name = name)

    override fun withVersionId(versionId: Int): TestCordappImpl = copy(versionId = versionId)

    override fun withTargetPlatformVersion(targetPlatformVersion: Int): TestCordappImpl = copy(targetPlatformVersion = targetPlatformVersion)

    override fun withConfig(config: Map<String, Any>): TestCordappImpl = copy(config = config)

    fun signJar(keyStorePath: Path? = null): TestCordappImpl = copy(signJar = true, keyStorePath = keyStorePath)

    fun withClasses(vararg classes: Class<*>): TestCordappImpl {
        return copy(classes = classes.filter { clazz -> packages.none { clazz.name.startsWith("$it.") } }.toSet())
    }
}
