package com.chrisgoac.gymlog.coach

import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.coach.CoachMessage
import com.chrisgoac.gymlog.domain.coach.CoachReportCategory
import com.chrisgoac.gymlog.domain.coach.CoachRepository
import com.chrisgoac.gymlog.domain.coach.CoachRole
import com.chrisgoac.gymlog.domain.coach.CoachStreamUpdate
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A scripted step of one fake turn. */
private sealed interface Step {
    data class Update(val update: CoachStreamUpdate) : Step
    data class Fail(val failure: CoachFailure) : Step
    data object End : Step
}

private class FakeCoachRepository : CoachRepository {
    val thread = MutableStateFlow<List<CoachMessage>>(emptyList())
    val sends = mutableListOf<Pair<String, String>>()
    val turns = mutableListOf<Channel<Step>>()
    val reports = mutableListOf<Triple<String, CoachReportCategory, String?>>()
    var reportFailure: CoachFailure? = null

    override fun observeThread(): Flow<List<CoachMessage>> = thread

    override fun send(clientMessageId: String, text: String): Flow<CoachStreamUpdate> = flow {
        sends += clientMessageId to text
        // Like the real repository: the question is stored first, once per id.
        if (thread.value.none { it.id == clientMessageId }) {
            thread.value = thread.value + CoachMessage(clientMessageId, CoachRole.USER, text, thread.value.size.toLong())
        }
        val channel = Channel<Step>(Channel.UNLIMITED).also { turns += it }
        for (step in channel) {
            when (step) {
                is Step.Update -> emit(step.update)
                is Step.Fail -> throw CoachException(step.failure)
                Step.End -> return@flow
            }
        }
    }

    override suspend fun report(responseId: String, category: CoachReportCategory, context: String?) {
        reportFailure?.let { throw CoachException(it) }
        reports += Triple(responseId, category, context)
    }

    /** Completes the last turn like the repository: stores the answer, then reports completion. */
    fun complete(responseId: String, text: String) {
        thread.value = thread.value + CoachMessage(responseId, CoachRole.ASSISTANT, text, thread.value.size.toLong())
        turns.last().trySend(Step.Update(CoachStreamUpdate.Completed(responseId)))
        turns.last().trySend(Step.End)
    }
}

private class FakeEntitlements(initial: Boolean) : EntitlementReader {
    val pro = MutableStateFlow(initial)
    override fun observeIsPro(): Flow<Boolean> = pro
}

@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeCoachRepository()
    private val entitlements = FakeEntitlements(true)
    private val online = MutableStateFlow(true)
    private var saved = SavedStateHandle()
    private var ids = 0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): CoachViewModel =
        CoachViewModel(repo, entitlements, online, saved, newMessageId = { "msg-${++ids}" }).also { advanceUntilIdle() }

    private fun TestScope.step(vararg steps: Step) {
        steps.forEach { repo.turns.last().trySend(it) }
        advanceUntilIdle()
    }

    private fun delta(text: String) = Step.Update(CoachStreamUpdate.Delta(text))

    @Test fun `not Pro shows the locked state and never sends`() = runTest(dispatcher) {
        entitlements.pro.value = false
        val vm = viewModel()

        assertEquals(CoachAccess.LOCKED, vm.state.value.access)
        vm.onDraftChange("¿Qué tal mi semana?")
        vm.send()
        vm.sendSuggestion(CoachViewModel.SUGGESTED_PROMPTS.first())
        advanceUntilIdle()

        assertTrue(repo.sends.isEmpty())
        assertFalse(vm.state.value.canSend)
    }

    @Test fun `a server PRO_REQUIRED locks the screen even if the device thought it was Pro`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDraftChange("hola")
        vm.send()
        advanceUntilIdle()
        step(Step.Fail(CoachFailure.PRO_REQUIRED))

        assertEquals(CoachAccess.LOCKED, vm.state.value.access)
        assertNull(vm.state.value.turn)
        vm.onDraftChange("otra")
        vm.send()
        advanceUntilIdle()
        assertEquals(1, repo.sends.size)
    }

    @Test fun `offline is explained, keeps the draft and queues nothing`() = runTest(dispatcher) {
        online.value = false
        val vm = viewModel()
        vm.onDraftChange("¿Subo peso?")
        assertFalse(vm.state.value.canSend)

        vm.send()
        advanceUntilIdle()

        assertTrue(repo.sends.isEmpty())
        assertEquals(CoachNotice.OFFLINE, vm.state.value.notice)
        assertEquals("¿Subo peso?", vm.state.value.draft)
        assertTrue("nothing stored", repo.thread.value.isEmpty())

        online.value = true
        advanceUntilIdle()
        assertTrue(vm.state.value.canSend)
    }

    @Test fun `suggested prompts are the PWA's and send at once`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(listOf("Analiza mi semana", "¿Por qué estoy estancado?", "¿Qué ejercicio sustituyo?"), CoachViewModel.SUGGESTED_PROMPTS)

        vm.sendSuggestion("¿Por qué estoy estancado?")
        advanceUntilIdle()

        assertEquals(listOf("msg-1" to "¿Por qué estoy estancado?"), repo.sends)
    }

    @Test fun `deltas accumulate into the ephemeral answer until the stored one takes over`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDraftChange("  ¿Subo peso?  ")
        vm.send()
        advanceUntilIdle()

        assertEquals("", vm.state.value.draft)
        assertEquals(TurnPhase.SENDING, vm.state.value.turn?.phase)
        assertFalse("one turn at a time", vm.state.value.canSend)

        step(Step.Update(CoachStreamUpdate.Started("resp-1")), delta("Sube "), delta("2,5 kg"))
        assertEquals(TurnPhase.STREAMING, vm.state.value.turn?.phase)
        assertEquals("Sube 2,5 kg", vm.state.value.turn?.text)

        repo.complete("resp-1", "Sube 2,5 kg")
        advanceUntilIdle()

        assertNull(vm.state.value.turn)
        assertEquals(listOf("msg-1", "resp-1"), vm.state.value.messages.map { it.id })
        assertEquals(listOf("msg-1" to "¿Subo peso?"), repo.sends)
    }

    @Test fun `the stream keeps running while no screen observes it (rotation) and the draft survives recreation`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDraftChange("¿Subo peso?")
        vm.send()
        advanceUntilIdle()
        // No collector of vm.state at all: the configuration change destroyed the screen.
        step(Step.Update(CoachStreamUpdate.Started("resp-1")), delta("Sube"), delta(" más"))
        assertEquals("Sube más", vm.state.value.turn?.text)

        vm.onDraftChange("siguiente pregunta")
        val recreated = viewModel() // process death: same SavedStateHandle
        assertEquals("siguiente pregunta", recreated.state.value.draft)
    }

    @Test fun `an interruption keeps the partial answer marked incomplete and retry reuses the message id`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDraftChange("¿Subo peso?")
        vm.send()
        advanceUntilIdle()
        step(Step.Update(CoachStreamUpdate.Started("resp-1")), delta("Sube"), Step.Fail(CoachFailure.INTERRUPTED))

        val turn = vm.state.value.turn!!
        assertEquals(TurnPhase.INCOMPLETE, turn.phase)
        assertEquals(CoachFailure.INTERRUPTED, turn.failure)
        assertEquals("Sube", turn.text)
        assertTrue(turn.canRetry)
        assertEquals("the partial answer is never stored", listOf("msg-1"), repo.thread.value.map { it.id })

        vm.retry()
        advanceUntilIdle()
        assertEquals(TurnPhase.SENDING, vm.state.value.turn?.phase)
        assertEquals("", vm.state.value.turn?.text)
        step(Step.Update(CoachStreamUpdate.Started("resp-1")), delta("Sube 2,5 kg"))
        repo.complete("resp-1", "Sube 2,5 kg")
        advanceUntilIdle()

        assertEquals(listOf("msg-1" to "¿Subo peso?", "msg-1" to "¿Subo peso?"), repo.sends)
        assertEquals("one question, one answer", listOf("msg-1", "resp-1"), vm.state.value.messages.map { it.id })
        assertNull(vm.state.value.turn)
    }

    @Test fun `a question left unanswered (screen left mid-answer, process death) is offered for retry with its id`() = runTest(dispatcher) {
        repo.thread.value = listOf(
            CoachMessage("old", CoachRole.USER, "antigua", 1),
            CoachMessage("old-r", CoachRole.ASSISTANT, "respuesta", 2),
            CoachMessage("msg-7", CoachRole.USER, "¿Subo peso?", 3),
        )
        val vm = viewModel()

        val turn = vm.state.value.turn!!
        assertEquals("msg-7", turn.clientMessageId)
        assertEquals(TurnPhase.INCOMPLETE, turn.phase)
        assertTrue(turn.canRetry)

        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("msg-7" to "¿Subo peso?"), repo.sends)
    }

    @Test fun `a dismissed unanswered question stays dismissed`() = runTest(dispatcher) {
        repo.thread.value = listOf(CoachMessage("msg-7", CoachRole.USER, "q", 1))
        val vm = viewModel()
        vm.dismissTurn()
        repo.thread.value = repo.thread.value.toList() // the thread re-emits (sync)
        advanceUntilIdle()
        assertNull(vm.state.value.turn)
    }

    @Test fun `retry while offline is refused with the offline notice`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDraftChange("q")
        vm.send()
        advanceUntilIdle()
        step(Step.Fail(CoachFailure.INTERRUPTED))
        online.value = false
        advanceUntilIdle()

        vm.retry()
        advanceUntilIdle()

        assertEquals(1, repo.sends.size)
        assertEquals(CoachNotice.OFFLINE, vm.state.value.notice)
        assertEquals(TurnPhase.INCOMPLETE, vm.state.value.turn?.phase)
    }

    @Test fun `a refused request cannot be retried and is dismissable`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDraftChange("q")
        vm.send()
        advanceUntilIdle()
        step(Step.Fail(CoachFailure.REJECTED))

        assertFalse(vm.state.value.turn!!.canRetry)
        vm.retry()
        advanceUntilIdle()
        assertEquals(1, repo.sends.size)
        vm.dismissTurn()
        assertNull(vm.state.value.turn)
        assertTrue(vm.state.value.canSend.not()) // empty draft
    }

    @Test fun `the report dialog sends the chosen category`() = runTest(dispatcher) {
        repo.thread.value = listOf(CoachMessage("m", CoachRole.USER, "q", 1), CoachMessage("resp-9", CoachRole.ASSISTANT, "a", 2))
        val vm = viewModel()

        vm.openReport("resp-9")
        assertFalse("a category is required", vm.state.value.report!!.canSubmit)
        vm.selectReportCategory(CoachReportCategory.MEDICAL)
        vm.submitReport()
        advanceUntilIdle()

        assertEquals(listOf(Triple("resp-9", CoachReportCategory.MEDICAL, null)), repo.reports)
        assertNull(vm.state.value.report)
        assertEquals(CoachNotice.REPORT_SENT, vm.state.value.notice)
    }

    @Test fun `a failed report keeps the dialog with its error`() = runTest(dispatcher) {
        repo.reportFailure = CoachFailure.INTERRUPTED
        val vm = viewModel()
        vm.openReport("resp-9")
        vm.selectReportCategory(CoachReportCategory.INACCURATE)
        vm.submitReport()
        advanceUntilIdle()

        assertEquals(CoachFailure.INTERRUPTED, vm.state.value.report?.failure)
        assertFalse(vm.state.value.report!!.sending)
        vm.closeReport()
        assertNull(vm.state.value.report)
    }

    @Test fun `a question longer than the server limit cannot be sent`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onDraftChange("x".repeat(2001))
        assertEquals(2000, vm.state.value.draft.length)
        vm.onDraftChange("   ")
        assertFalse(vm.state.value.canSend)
    }
}
