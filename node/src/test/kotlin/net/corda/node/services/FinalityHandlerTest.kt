package net.corda.node.services

import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.StateMachineRunId
import net.corda.core.internal.cordapp.CordappResolver
import net.corda.core.internal.packageName
import net.corda.core.toFuture
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.node.services.statemachine.StaffedFlowHospital.*
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import rx.Observable

class FinalityHandlerTest {
    private val mockNet = InternalMockNetwork()

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `sent to flow hospital on error and attempted retry on node restart`() {
        // Setup a network where only Alice has the finance CorDapp and it sends a cash tx to Bob who doesn't have the
        // CorDapp. Bob's FinalityHandler will error when validating the tx.
        val alice = mockNet.createNode(InternalMockNodeParameters(
                legalName = ALICE_NAME,
                additionalCordapps = setOf(FINANCE_CORDAPP)
        ))

        var bob = mockNet.createNode(InternalMockNodeParameters(
                legalName = BOB_NAME,
                // The node disables the FinalityHandler completely if there are no old CorDapps loaded, so we need to add
                // a token old CorDapp to keep the handler running.
                additionalCordapps = setOf(findCordapp(javaClass.packageName).withTargetPlatformVersion(3))
        ))

        val stx = alice.issueCashTo(bob)
        val finalityHandlerId = bob.trackFinalityHandlerId().run {
            alice.finaliseWithOldApi(stx)
            getOrThrow()
        }

        bob.assertFlowSentForObservationDueToConstraintError(finalityHandlerId)
        assertThat(bob.getTransaction(stx.id)).isNull()

        bob = mockNet.restartNode(bob)
        // Since we've not done anything to fix the orignal error, we expect the finality handler to be sent to the hospital
        // again on restart
        bob.assertFlowSentForObservationDueToConstraintError(finalityHandlerId)
        assertThat(bob.getTransaction(stx.id)).isNull()
    }

    @Test
    fun `disabled if there are no old CorDapps loaded`() {
        val alice = mockNet.createNode(InternalMockNodeParameters(
                legalName = ALICE_NAME,
                additionalCordapps = setOf(FINANCE_CORDAPP)
        ))

        val bob = mockNet.createNode(InternalMockNodeParameters(
                legalName = BOB_NAME,
                // Make sure the target version is 4, and not the current platform version which may be greater
                additionalCordapps = setOf(FINANCE_CORDAPP.withTargetPlatformVersion(4))
        ))

        val stx = alice.issueCashTo(bob)
        val finalityFuture = alice.finaliseWithOldApi(stx)

        val record = bob.medicalRecordsOfType<MedicalRecord.SessionInit>()
                .toBlocking()
                .first()
        assertThat(record.outcome).isEqualTo(Outcome.OVERNIGHT_OBSERVATION)
        assertThat(record.sender).isEqualTo(alice.info.singleIdentity())
        assertThat(record.initiatorFlowClassName).isEqualTo(FinalityFlow::class.java.name)

        assertThat(bob.getTransaction(stx.id)).isNull()

        // Drop the session-init so that Alice gets the error message
        assertThat(finalityFuture).isNotDone()
        bob.smm.flowHospital.dropSessionInit(record.id)
        mockNet.runNetwork()
        assertThatThrownBy {
            finalityFuture.getOrThrow()
        }.hasMessageContaining("Counterparty attempting to use the old insecure API of FinalityFlow")
    }

    private fun TestStartedNode.issueCashTo(recipient: TestStartedNode): SignedTransaction {
        return TransactionBuilder(mockNet.defaultNotaryIdentity).let {
            Cash().generateIssue(
                    it,
                    1000.POUNDS.issuedBy(info.singleIdentity().ref(0)),
                    recipient.info.singleIdentity(),
                    mockNet.defaultNotaryIdentity
            )
            services.signInitialTransaction(it)
        }
    }

    private fun TestStartedNode.trackFinalityHandlerId(): CordaFuture<StateMachineRunId> {
        return smm
                .track()
                .updates
                .filter { it.logic is FinalityHandler }
                .map { it.logic.runId }
                .toFuture()
    }

    private fun TestStartedNode.finaliseWithOldApi(stx: SignedTransaction): CordaFuture<SignedTransaction> {
        return CordappResolver.withCordapp(targetPlatformVersion = 3) {
            @Suppress("DEPRECATION")
            services.startFlow(FinalityFlow(stx)).resultFuture.apply {
                mockNet.runNetwork()
            }
        }
    }

    private inline fun <reified R : MedicalRecord> TestStartedNode.medicalRecordsOfType(): Observable<R> {
        return smm
                .flowHospital
                .track()
                .let { it.updates.startWith(it.snapshot) }
                .ofType(R::class.java)
    }

    private fun TestStartedNode.assertFlowSentForObservationDueToConstraintError(runId: StateMachineRunId) {
        val observation = medicalRecordsOfType<MedicalRecord.Flow>()
                .filter { it.flowId == runId }
                .toBlocking()
                .first()
        assertThat(observation.outcome).isEqualTo(Outcome.OVERNIGHT_OBSERVATION)
        assertThat(observation.by).contains(FinalityDoctor)
        val error = observation.errors.single()
        assertThat(error).isInstanceOf(TransactionVerificationException.ContractConstraintRejection::class.java)
    }

    private fun TestStartedNode.getTransaction(id: SecureHash): SignedTransaction? {
        return services.validatedTransactions.getTransaction(id)
    }
}
