package com.chrisgoac.gymlog

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards what ships in the Play bundle. Runs in both unit-test variants; the checks that only hold
 * for the release build type (`./gradlew testReleaseUnitTest`) are skipped in the debug suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReleaseConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val isRelease = BuildConfig.BUILD_TYPE == "release"

    @Test fun packageAndSdkLevelsAreTheOnesPlayKnows() {
        assertEquals("com.chrisgoac.gymlog", BuildConfig.APPLICATION_ID)
        assertEquals("com.chrisgoac.gymlog", context.packageName)
        val info = context.applicationInfo
        assertEquals(26, info.minSdkVersion)
        assertEquals(36, info.targetSdkVersion)
    }

    @Test fun versionIsAPositiveCodeAndASemanticName() {
        assertTrue(BuildConfig.VERSION_CODE >= 1)
        val name = if (isRelease) BuildConfig.VERSION_NAME else BuildConfig.VERSION_NAME.removeSuffix("-debug")
        assertTrue("versionName '${BuildConfig.VERSION_NAME}' is not MAJOR.MINOR.PATCH", SEMVER.matches(name))
    }

    @Test fun everyBackendAndWebUrlIsHttps() {
        for (url in listOf(BuildConfig.API_BASE_URL, BuildConfig.PRIVACY_POLICY_URL, BuildConfig.ACCOUNT_DELETION_URL)) {
            val uri = URI(url)
            assertEquals("$url must use https", "https", uri.scheme)
            assertFalse("$url must have a host", uri.host.isNullOrBlank())
        }
    }

    @Test fun manifestKeepsDataOnTheDeviceAndRefusesCleartext() {
        val info = context.applicationInfo
        assertEquals("allowBackup", 0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals("usesCleartextTraffic", 0, info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        // The backup-rule references are @hide on ApplicationInfo: read them from the merged manifest.
        val application = mergedApplicationAttributes()
        assertEquals("@xml/data_extraction_rules", application["dataExtractionRules"])
        assertEquals("@xml/backup_rules", application["fullBackupContent"])
        assertEquals("false", application["usesCleartextTraffic"])
    }

    @Test fun theLauncherShowsTheAppIcon() {
        val info = context.applicationInfo
        assertNotEquals("android:icon", 0, info.icon)
        assertEquals("mipmap", context.resources.getResourceTypeName(info.icon))
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val launcher = pm.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        assertEquals("MainActivity shows the app icon", info.icon, launcher.iconResource.takeIf { it != 0 } ?: info.icon)
        val application = mergedApplicationAttributes()
        assertEquals("@mipmap/ic_launcher", application["icon"])
        assertEquals("@mipmap/ic_launcher_round", application["roundIcon"])
    }

    @Test fun releaseIsNotDebuggableAndHasNoDebugBuildConfig() {
        assumeTrue("release build type only", isRelease)
        assertFalse(BuildConfig.DEBUG)
        assertEquals("debuggable", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertFalse("merged manifest marks the app debuggable", mergedApplicationAttributes()["debuggable"] == "true")
        assertFalse("release must not ship a Clerk development key", BuildConfig.CLERK_PUBLISHABLE_KEY.startsWith("pk_test_"))
    }

    @Test fun packagedStringsContainNoSecretsOrDebugMenus() {
        val strings = packagedStrings()
        assertTrue("found ${strings.size} packaged strings", strings.size > 50)
        for ((name, value) in strings) {
            for (pattern in SECRET_MARKERS) {
                assertFalse("string '$name' contains '$pattern'", value.contains(pattern))
            }
            assertFalse("string '$name' looks like a debug menu", DEBUG_MENU.containsMatchIn(name))
        }
    }

    /**
     * `android:` attributes of `<application>` in the manifest this unit-test variant was merged
     * from (AGP points to it in `com/android/tools/test_config.properties`).
     */
    private fun mergedApplicationAttributes(): Map<String, String> {
        val config = Properties().apply {
            ClassLoader.getSystemResourceAsStream("com/android/tools/test_config.properties")!!.use(::load)
        }
        val manifest = File(config.getProperty("android_merged_manifest"))
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val application = document.getElementsByTagName("application").item(0)
        val attributes = application.attributes
        return (0 until attributes.length).map(attributes::item)
            .filter { it.namespaceURI == ANDROID_NS }
            .associate { it.localName to it.nodeValue }
    }

    /** Every string resource in the packaged table (app, feature modules and libraries). */
    private fun packagedStrings(): Map<String, String> {
        val res = context.resources
        val typeBits = R.string.app_name and 0xFFFF0000.toInt()
        val out = LinkedHashMap<String, String>()
        var entry = 0
        while (true) {
            val id = typeBits or entry
            val name = try {
                res.getResourceEntryName(id)
            } catch (_: Resources.NotFoundException) {
                break
            }
            out[name] = res.getString(id)
            entry++
        }
        return out
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
        val SECRET_MARKERS = listOf("sk_live_", "sk_test_", "pk_test_", "whsec_", "CRON_SECRET", "-----BEGIN", "AKIA", "AIza")
        val DEBUG_MENU = Regex("""(^|_)debug(_|$)""", RegexOption.IGNORE_CASE)
    }
}
