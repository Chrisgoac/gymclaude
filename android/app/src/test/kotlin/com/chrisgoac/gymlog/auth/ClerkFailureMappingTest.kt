package com.chrisgoac.gymlog.auth

import com.chrisgoac.gymlog.domain.auth.AuthFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class ClerkFailureMappingTest {
    private fun map(code: String? = null, status: Int? = null, network: Boolean = false) =
        clerkFailure(code, status, network)

    @Test fun networkWinsOverEverything() = assertEquals(AuthFailure.Network, map("form_code_incorrect", network = true))

    @Test fun rateLimitByStatusOrCode() {
        assertEquals(AuthFailure.RateLimited, map(status = 429))
        assertEquals(AuthFailure.RateLimited, map("too_many_requests"))
    }

    @Test fun knownCodes() {
        assertEquals(AuthFailure.InvalidCode, map("form_code_incorrect"))
        assertEquals(AuthFailure.ExpiredCode, map("verification_expired"))
        assertEquals(AuthFailure.InvalidCredentials, map("form_password_incorrect"))
        assertEquals(AuthFailure.AccountNotFound, map("form_identifier_not_found"))
        assertEquals(AuthFailure.AccountExists, map("form_identifier_exists"))
        assertEquals(AuthFailure.WeakPassword, map("form_password_pwned"))
        assertEquals(AuthFailure.WeakPassword, map("form_password_length_too_short"))
    }

    @Test fun unknownKeepsOnlyTheCodeForDiagnostics() = assertEquals(AuthFailure.Unknown("something_new"), map("something_new", 422))
}
