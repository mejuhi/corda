package net.corda.traderdemo

import io.github.classgraph.ClassGraph
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.internal.*
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.millis
import net.corda.finance.DOLLARS
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.node.services.Permissions.Companion.all
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.core.BOC_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.DUMMY_BANK_B_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.InProcess
import net.corda.testing.driver.OutOfProcess
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import net.corda.testing.node.internal.ProcessUtilities
import net.corda.testing.node.internal.poll
import net.corda.traderdemo.flow.CommercialPaperIssueFlow
import net.corda.traderdemo.flow.SellerFlow
import org.apache.commons.lang.SystemUtils
import org.apache.sshd.common.util.OsUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.jar.JarInputStream
import java.util.jar.Manifest

class TraderDemoTest {
//    @Test
    fun `runs trader demo`() {
        val demoUser = User("demo", "demo", setOf(startFlow<SellerFlow>(), all()))
        val bankUser = User("user1", "test", permissions = setOf(
                startFlow<CashIssueFlow>(),
                startFlow<CashPaymentFlow>(),
                startFlow<CommercialPaperIssueFlow>(),
                all()))
        driver(DriverParameters(startNodesInProcess = true, inMemoryDB = false, extraCordappPackagesToScan = listOf("net.corda.finance"))) {
            val (nodeA, nodeB, bankNode) = listOf(
                    startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = DUMMY_BANK_B_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = BOC_NAME, rpcUsers = listOf(bankUser))
            ).map { (it.getOrThrow() as InProcess) }

            val (nodeARpc, nodeBRpc) = listOf(nodeA, nodeB).map {
                val client = CordaRPCClient(it.rpcAddress)
                client.start(demoUser.username, demoUser.password).proxy
            }
            val nodeBankRpc = let {
                val client = CordaRPCClient(bankNode.rpcAddress)
                client.start(bankUser.username, bankUser.password).proxy
            }

            val clientA = TraderDemoClientApi(nodeARpc)
            val clientB = TraderDemoClientApi(nodeBRpc)
            val clientBank = TraderDemoClientApi(nodeBankRpc)

            val originalACash = clientA.cashCount // A has random number of issued amount
            val expectedBCash = clientB.cashCount + 1
            val expectedPaper = listOf(clientA.commercialPaperCount + 1, clientB.commercialPaperCount)

            clientBank.runIssuer(amount = 100.DOLLARS, buyerName = nodeA.services.myInfo.singleIdentity().name, sellerName = nodeB.services.myInfo.singleIdentity().name)
            clientB.runSeller(buyerName = nodeA.services.myInfo.singleIdentity().name, amount = 5.DOLLARS)

            assertThat(clientA.cashCount).isGreaterThan(originalACash)
            assertThat(clientB.cashCount).isEqualTo(expectedBCash)
            // Wait until A receives the commercial paper
            val executor = Executors.newScheduledThreadPool(1)
            poll(executor, "A to be notified of the commercial paper", pollInterval = 100.millis) {
                val actualPaper = listOf(clientA.commercialPaperCount, clientB.commercialPaperCount)
                if (actualPaper == expectedPaper) Unit else null
            }.getOrThrow()
            executor.shutdown()
            assertThat(clientA.dollarCashBalance).isEqualTo(95.DOLLARS)
            assertThat(clientB.dollarCashBalance).isEqualTo(5.DOLLARS)
        }
    }

//    @Test
    fun `Tudor test`() {
        driver(DriverParameters(startNodesInProcess = false, inMemoryDB = false, extraCordappPackagesToScan = listOf("net.corda.finance"))) {
            val demoUser = User("demo", "demo", setOf(startFlow<SellerFlow>(), all()))
            val bankUser = User("user1", "test", permissions = setOf(all()))
            val (nodeA, nodeB, bankNode) = listOf(
                    startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = DUMMY_BANK_B_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = BOC_NAME, rpcUsers = listOf(bankUser))
            ).map { (it.getOrThrow() as OutOfProcess) }

            val nodeBRpc = CordaRPCClient(nodeB.rpcAddress).start(demoUser.username, demoUser.password).proxy
            val nodeARpc = CordaRPCClient(nodeA.rpcAddress).start(demoUser.username, demoUser.password).proxy
            val nodeBankRpc = let {
                val client = CordaRPCClient(bankNode.rpcAddress)
                client.start(bankUser.username, bankUser.password).proxy
            }

            TraderDemoClientApi(nodeBankRpc).runIssuer(amount = 100.DOLLARS, buyerName = nodeA.nodeInfo.singleIdentity().name, sellerName = nodeB.nodeInfo.singleIdentity().name)
            val stxFuture = nodeBRpc.startFlow(::SellerFlow, nodeA.nodeInfo.singleIdentity(), 5.DOLLARS).returnValue
            nodeARpc.stateMachinesFeed().updates.toBlocking().first() // wait until initiated flow starts
            nodeA.stop()
            startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = listOf(demoUser), customOverrides = mapOf("p2pAddress" to nodeA.p2pAddress.toString()))
            stxFuture.getOrThrow()
        }
    }

    @Test
    fun d() {
        val scanResult = ClassGraph()
                .whitelistPackages("net.corda.finance", javaClass.packageName)
                .scan()

        scanResult.use {
            scanResult.allResources
                    .asSequence()
                    .map { resource ->
                        val path = resource.classpathElementURL.toPath()
                        if (path.toString().endsWith(".jar")) path else findProjectRoot(path)
                    }
                    .distinct()
                    .forEach {
                        if (it.toString().endsWith(".jar")) {
                            val manifest: Manifest? = JarInputStream(it.inputStream()).use { it.manifest }
                            println(it)
                            manifest?.mainAttributes?.entries?.forEach { println(it) }
                        } else {
                            val gradlew = findGradlewDir(it) / (if (SystemUtils.IS_OS_WINDOWS) "gradlew.bat" else "gradlew")
                            ProcessBuilder(gradlew.toString(), "jar").directory(it.toFile()).inheritIO().start().waitFor()
                            println(it)
                        }
                        println()
                    }
        }
    }

    private fun findProjectRoot(path: Path): Path {
        var current = path
        while (true) {
            if ((current / "build.gradle").exists()) {
                return current
            }
            current = current.parent
        }
    }

    private fun findGradlewDir(path: Path): Path {
        var current = path
        while (true) {
            if ((current / "gradlew").exists() && (current / "gradlew.bat").exists()) {
                return current
            }
            current = current.parent
        }
    }
}
