package com.chrisgoac.gymlog.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** On-device per-user database isolation (the JVM twin is UserIsolationRobolectricTest). */
@RunWith(AndroidJUnit4::class)
class UserIsolationTest {
    private val checks = UserIsolationChecks(ApplicationProvider.getApplicationContext())

    @After fun tearDown() = checks.cleanUp()

    @Test fun twoUsersNeverSeeEachOthersData() = checks.twoUsersNeverSeeEachOthersData()
    @Test fun openingTheSameUserTwiceReturnsTheOpenDatabase() = checks.openingTheSameUserTwiceReturnsTheOpenDatabase()
    @Test fun fileNamesAreHashedAndDistinct() = checks.fileNamesAreHashedAndDistinct()
    @Test fun closeAndDeleteRemovesOnlyThatUsersFiles() = checks.closeAndDeleteRemovesOnlyThatUsersFiles()
    @Test fun closeKeepsTheFile() = checks.closeKeepsTheFile()
}
