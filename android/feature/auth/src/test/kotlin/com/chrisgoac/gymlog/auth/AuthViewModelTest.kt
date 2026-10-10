package com.chrisgoac.gymlog.auth

import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val gateway = FakeAuthGateway()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel() = AuthViewModel(gateway).also { advanceUntilIdle() }

    @Test fun emailCodeSignInSendsCodeThenVerifies() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("  ana@example.com ")
        vm.submit(); advanceUntilIdle()

        assertEquals(listOf("startEmailCodeSignIn:ana@example.com"), gateway.calls)
        assertEquals(AuthStage.Code, vm.state.value.stage)

        vm.onCodeChange("123456")
        vm.submit(); advanceUntilIdle()
        assertEquals("verifyEmailCodeSignIn:123456", gateway.calls.last())
        assertNull(vm.state.value.message)
    }

    @Test fun invalidEmailIsRejectedWithoutCallingTheGateway() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("not-an-email")
        vm.submit(); advanceUntilIdle()
        assertTrue(gateway.calls.isEmpty())
        assertEquals(AuthMessage.InvalidEmail, vm.state.value.message)
    }

    @Test fun passwordSignInRequiresPasswordAndUsesIt() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.togglePasswordSignIn()
        vm.onEmailChange("ana@example.com")
        vm.submit(); advanceUntilIdle()
        assertEquals(AuthMessage.MissingPassword, vm.state.value.message)
        assertTrue(gateway.calls.isEmpty())

        vm.onPasswordChange("s3cret-pass")
        vm.submit(); advanceUntilIdle()
        assertEquals(listOf("signInWithPassword:ana@example.com"), gateway.calls)
    }

    @Test fun signUpStartsWithEmailAndPasswordThenVerifies() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setMode(AuthMode.SignUp)
        vm.onEmailChange("new@example.com")
        vm.onPasswordChange("s3cret-pass")
        vm.submit(); advanceUntilIdle()
        assertEquals(AuthStage.Code, vm.state.value.stage)

        vm.onCodeChange("654321")
        vm.submit(); advanceUntilIdle()
        assertEquals(listOf("startSignUp:new@example.com:true", "verifySignUp:654321"), gateway.calls)
    }

    @Test fun gatewayFailureIsShownAndClearsBusy() = runTest(dispatcher) {
        gateway.next = AuthStep.Failed(AuthFailure.RateLimited)
        val vm = newViewModel()
        vm.onEmailChange("ana@example.com")
        vm.submit(); advanceUntilIdle()
        assertEquals(AuthMessage.Gateway(AuthFailure.RateLimited), vm.state.value.message)
        assertFalse(vm.state.value.busy)
        assertEquals(AuthStage.Credentials, vm.state.value.stage)
    }

    @Test fun googleCancellationIsNotAnError() = runTest(dispatcher) {
        gateway.next = AuthStep.Cancelled
        val vm = newViewModel()
        vm.signInWithGoogle(); advanceUntilIdle()
        assertEquals(listOf("signInWithGoogle"), gateway.calls)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test fun secondSubmitWhileBusyIsIgnored() = runTest(dispatcher) {
        val pending = CompletableDeferred<AuthStep>()
        gateway.pending = pending
        val vm = newViewModel()
        vm.onEmailChange("ana@example.com")
        vm.submit(); advanceUntilIdle()
        assertTrue(vm.state.value.busy)
        vm.submit(); vm.signInWithGoogle(); advanceUntilIdle()
        assertEquals(1, gateway.calls.size)
        pending.complete(AuthStep.CodeSent); advanceUntilIdle()
        assertFalse(vm.state.value.busy)
    }

    @Test fun backFromCodeReturnsToCredentialsAndDropsCode() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onEmailChange("ana@example.com")
        vm.submit(); advanceUntilIdle()
        vm.onCodeChange("12")
        vm.back()
        assertEquals(AuthStage.Credentials, vm.state.value.stage)
        assertEquals("", vm.state.value.code)
        assertEquals("ana@example.com", vm.state.value.email)
    }

    @Test fun signedInSessionWipesSecretsFromTheForm() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.togglePasswordSignIn()
        vm.onEmailChange("ana@example.com")
        vm.onPasswordChange("s3cret-pass")
        gateway.state.value = AuthSessionState.SignedIn("user_1"); advanceUntilIdle()
        assertEquals("", vm.state.value.password)
        assertEquals("", vm.state.value.code)
    }
}

private class FakeAuthGateway : AuthGateway {
    override val state = MutableStateFlow<AuthSessionState>(AuthSessionState.SignedOut)
    val calls = mutableListOf<String>()
    var next: AuthStep? = null
    var pending: CompletableDeferred<AuthStep>? = null

    private suspend fun answer(call: String, default: AuthStep): AuthStep {
        calls += call
        pending?.let { return it.await() }
        return next ?: default
    }

    override suspend fun startEmailCodeSignIn(email: String) = answer("startEmailCodeSignIn:$email", AuthStep.CodeSent)
    override suspend fun verifyEmailCodeSignIn(code: String) = answer("verifyEmailCodeSignIn:$code", AuthStep.Completed)
    override suspend fun signInWithPassword(email: String, password: String) = answer("signInWithPassword:$email", AuthStep.Completed)
    override suspend fun startSignUp(email: String, password: String?) = answer("startSignUp:$email:${password != null}", AuthStep.CodeSent)
    override suspend fun verifySignUp(code: String) = answer("verifySignUp:$code", AuthStep.Completed)
    override suspend fun signInWithGoogle() = answer("signInWithGoogle", AuthStep.Completed)
    override suspend fun signOut() = answer("signOut", AuthStep.Completed)
}
