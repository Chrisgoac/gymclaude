package com.chrisgoac.gymlog.database

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM twin of the instrumented [UserIsolationTest]: same checks, file-backed SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserIsolationRobolectricTest {
    private val checks = UserIsolationChecks(ApplicationProvider.getApplicationContext())

    @After fun tearDown() = checks.cleanUp()

    @Test fun twoUsersNeverSeeEachOthersData() = checks.twoUsersNeverSeeEachOthersData()
    @Test fun openingTheSameUserTwiceReturnsTheOpenDatabase() = checks.openingTheSameUserTwiceReturnsTheOpenDatabase()
    @Test fun fileNamesAreHashedAndDistinct() = checks.fileNamesAreHashedAndDistinct()
    @Test fun closeAndDeleteRemovesOnlyThatUsersFiles() = checks.closeAndDeleteRemovesOnlyThatUsersFiles()
    @Test fun closeKeepsTheFile() = checks.closeKeepsTheFile()
}
