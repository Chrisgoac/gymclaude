package com.chrisgoac.gymlog.billing

import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.devicesettings.UserScopedPreferenceStores
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreEntitlementCacheTest {
    private lateinit var dir: File
    private val stores = mutableListOf<UserScopedPreferenceStores>()

    @Before fun setUp() { dir = Files.createTempDirectory("entitlement-cache").toFile() }

    @After fun tearDown() {
        stores.forEach { s -> listOf("user_1", "user_2").forEach { s.forget(UserDatabaseManagerImpl.userHash(it)) } }
        dir.deleteRecursively()
    }

    /** A fresh "process": new DataStore instances over the same files. */
    private fun open(): DataStoreEntitlementCache = DataStoreEntitlementCache(UserScopedPreferenceStores(dir).also { stores += it })

    @Test
    fun roundTripsPerAccountAcrossProcesses() = runTest {
        val first = open()
        assertNull(first.read("user_1"))
        first.write("user_1", JSON)
        stores.single().forget(UserDatabaseManagerImpl.userHash("user_1")) // release the file before reopening

        val second = open()
        assertEquals(JSON, second.read("user_1"))
        assertNull(second.read("user_2")) // another account sees nothing
        assertTrue(File(dir, "users/${UserDatabaseManagerImpl.userHash("user_1")}").isDirectory)
    }

    @Test
    fun writesAfterForgetAreDroppedUntilTheAccountIsReadAgain() = runTest {
        val cache = open()
        val hash = UserDatabaseManagerImpl.userHash("user_2")

        cache.forget(hash)
        cache.write("user_2", JSON)
        assertFalse(File(dir, "users/$hash").exists()) // nothing recreated for the deleted account

        assertNull(cache.read("user_2")) // signs in again
        cache.write("user_2", JSON)
        assertEquals(JSON, cache.read("user_2"))
    }

    private companion object {
        const val JSON = """{"tier":"PRO","productId":"gymlog_pro_monthly","state":"ACTIVE","validUntil":2,"serverTime":1}"""
    }
}
