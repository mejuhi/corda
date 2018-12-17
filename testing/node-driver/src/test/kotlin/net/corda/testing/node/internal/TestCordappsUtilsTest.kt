package net.corda.testing.node.internal

import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_CONTRACT_NAME
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_WORKFLOW_NAME
import net.corda.core.internal.cordapp.CordappImpl.Companion.TARGET_PLATFORM_VERSION
import net.corda.core.internal.cordapp.get
import net.corda.core.internal.inputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.jar.JarInputStream

class TestCordappsUtilsTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `test simplifyScanPackages`() {
        assertThat(simplifyScanPackages(emptyList())).isEmpty()
        assertThat(simplifyScanPackages(listOf("com.foo.bar"))).containsExactlyInAnyOrder("com.foo.bar")
        assertThat(simplifyScanPackages(listOf("com.foo", "com.foo"))).containsExactlyInAnyOrder("com.foo")
        assertThat(simplifyScanPackages(listOf("com.foo", "com.bar"))).containsExactlyInAnyOrder("com.foo", "com.bar")
        assertThat(simplifyScanPackages(listOf("com.foo", "com.foo.bar"))).containsExactlyInAnyOrder("com.foo")
        assertThat(simplifyScanPackages(listOf("com.foo.bar", "com.foo"))).containsExactlyInAnyOrder("com.foo")
        assertThat(simplifyScanPackages(listOf("com.foobar", "com.foo.bar"))).containsExactlyInAnyOrder("com.foobar", "com.foo.bar")
        assertThat(simplifyScanPackages(listOf("com.foobar", "com.foo"))).containsExactlyInAnyOrder("com.foobar", "com.foo")
    }

    @Test
    fun `packageAsJar writes out the CorDapp info into the manifest`() {
        val cordapp = findCordapp("net.corda.testing.node.internal")
                .withTargetPlatformVersion(123)
                .withName("TestCordappsUtilsTest")

        val jarFile = packageAsJar(cordapp)
        JarInputStream(jarFile.inputStream()).use {
            assertThat(it.manifest[TARGET_PLATFORM_VERSION]).isEqualTo("123")
            assertThat(it.manifest[CORDAPP_CONTRACT_NAME]).isEqualTo("TestCordappsUtilsTest")
            assertThat(it.manifest[CORDAPP_WORKFLOW_NAME]).isEqualTo("TestCordappsUtilsTest")
        }
    }

    @Test
    fun `packageAsJar on leaf package`() {
        val entries = packageAsJarThenReadBack(findCordapp("net.corda.testing.node.internal"))

        assertThat(entries).contains(
                "net/corda/testing/node/internal/TestCordappsUtilsTest.class",
                "net/corda/testing/node/internal/resource.txt" // Make sure non-class resource files are also picked up
        ).doesNotContain(
                "net/corda/testing/node/MockNetworkTest.class"
        )

        // Make sure the MockNetworkTest class does actually exist to ensure the above is not a false-positive
        assertThat(javaClass.classLoader.getResource("net/corda/testing/node/MockNetworkTest.class")).isNotNull()
    }

    @Test
    fun `packageAsJar on package with sub-packages`() {
        val entries = packageAsJarThenReadBack(findCordapp("net.corda.testing.node"))

        assertThat(entries).contains(
                "net/corda/testing/node/internal/TestCordappsUtilsTest.class",
                "net/corda/testing/node/internal/resource.txt",
                "net/corda/testing/node/MockNetworkTest.class"
        )
    }

    @Test
    fun `packageAsJar on single class`() {
        val entries = packageAsJarThenReadBack(cordappWithClasses(InternalMockNetwork::class.java))

        assertThat(entries).containsOnly("${InternalMockNetwork::class.java.name.replace('.', '/')}.class")
    }

    private fun packageAsJar(cordapp: TestCordappImpl): Path {
        val jarFile = tempFolder.newFile().toPath()
        cordapp.packageAsJar(jarFile)
        return jarFile
    }

    private fun packageAsJarThenReadBack(cordapp: TestCordappImpl): List<String> {
        val jarFile = packageAsJar(cordapp)
        val entries = ArrayList<String>()
        JarInputStream(jarFile.inputStream()).use {
            while (true) {
                val e = it.nextJarEntry ?: break
                entries += e.name
                it.closeEntry()
            }
        }
        return entries
    }
}
