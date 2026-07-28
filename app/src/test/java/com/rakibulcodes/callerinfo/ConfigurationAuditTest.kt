package com.rakibulcodes.callerinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigurationAuditTest {
    @Test
    fun manifestDisablesBackupAndMessageAccess() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android.permission.READ_CALL_LOG"))
        assertFalse(manifest.contains("android.permission.READ_SMS"))
    }

    @Test
    fun backupRulesExcludeEveryOwnedDomain() {
        val rules = sourceFile("src/main/res/xml/backup_rules.xml").readText()

        backupDomains.forEach { domain ->
            assertTrue(domain, rules.contains("<exclude domain=\"$domain\" path=\".\""))
        }
    }

    @Test
    fun extractionRulesExcludeCloudAndTransferData() {
        val rules = sourceFile("src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("<device-transfer>"))
        backupDomains.forEach { domain ->
            assertTrue(domain, occurrences(rules, "domain=\"$domain\"") == 2)
        }
    }

    @Test
    fun legacyReceiverIsRemovedAndScreeningServiceRemainsProtected() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains(".CallReceiver"))
        assertTrue(manifest.contains(".CallerScreeningService"))
        assertTrue(manifest.contains("android.permission.BIND_SCREENING_SERVICE"))
        assertTrue(manifest.contains("android.telecom.CallScreeningService"))
    }

    @Test
    fun applicationIdentityAndSupportedAbisRemainExplicit() {
        val gradle = sourceFile("build.gradle.kts").readText()

        assertTrue(gradle.contains("applicationId = \"com.rakibulcodes.callerinfo.test\""))
        assertTrue(gradle.contains("abiFilters.add(\"arm64-v8a\")"))
        assertTrue(gradle.contains("abiFilters.add(\"x86_64\")"))
        assertFalse(gradle.contains("abiFilters.add(\"armeabi-v7a\")"))
        assertFalse(gradle.contains("abiFilters.add(\"x86\")"))
        assertTrue(sourceFile("src/main/jniLibs/arm64-v8a/libtdjni.so").length() > 0)
        assertTrue(sourceFile("src/main/jniLibs/x86_64/libtdjni.so").length() > 0)
    }

    @Test
    fun visibleNameAndLauncherPaletteRemainConfigured() {
        val strings = sourceFile("src/main/res/values/strings.xml").readText()
        val activity = sourceFile("src/main/res/layout/activity_main.xml").readText()
        val overlay = sourceFile("src/main/res/layout/layout_overlay_card.xml").readText()
        val background =
            sourceFile("src/main/res/values/ic_launcher_background.xml").readText()
        val foreground =
            sourceFile("src/main/res/drawable/ic_launcher_foreground.xml").readText()

        assertTrue(strings.contains("<string name=\"app_name\">Caller Info Test</string>"))
        assertTrue(activity.contains("app:title=\"@string/app_name\""))
        assertTrue(overlay.contains("android:text=\"@string/app_name\""))
        assertTrue(background.contains("#5E35B1"))
        assertTrue(foreground.contains("311B92"))
        assertTrue(foreground.contains("#D1C4E9"))
    }

    @Test
    fun diagnosticsRemainLocalAndRefreshFromCurrentState() {
        val preview = sourceFile(
            "src/main/java/com/rakibulcodes/callerinfo/NumberFormatPreview.kt"
        ).readText()
        val activity = sourceFile(
            "src/main/java/com/rakibulcodes/callerinfo/MainActivity.kt"
        ).readText()

        assertFalse(preview.contains("SharedPreferences"))
        assertFalse(preview.contains("CallerInfoRepository"))
        assertFalse(preview.contains("CallLog"))
        assertFalse(preview.contains("ContactsContract"))
        assertFalse(preview.contains("http"))
        assertTrue(activity.contains("override fun onResume()"))
        assertTrue(activity.contains("refreshAppStatus()"))
    }

    @Test
    fun screeningResponsePrecedesOptionalProcessing() {
        val service = sourceFile(
            "src/main/java/com/rakibulcodes/callerinfo/CallerScreeningService.kt"
        ).readText()
        val coordinator = sourceFile(
            "src/main/java/com/rakibulcodes/callerinfo/CallScreeningSafeguards.kt"
        ).readText()

        val responseIndex = coordinator.indexOf("responder.respond()")
        val dispatchIndex = coordinator.indexOf("dispatcher.dispatch()")
        assertTrue(responseIndex >= 0)
        assertTrue(dispatchIndex > responseIndex)
        assertTrue(service.contains(".setDisallowCall(false)"))
        assertTrue(service.contains(".setRejectCall(false)"))
        assertTrue(service.contains(".setSilenceCall(false)"))
        assertFalse(service.contains("Thread.sleep"))
        assertFalse(service.contains("delay(5000"))
    }

    @Test
    fun transientDiagnosticsDoNotChangePersistence() {
        val source = sourceFile(
            "src/main/java/com/rakibulcodes/callerinfo/CallerLookupSource.kt"
        ).readText()
        val entity = sourceFile(
            "src/main/java/com/rakibulcodes/callerinfo/data/database/CallerInfoEntity.kt"
        ).readText()
        val preferences = sourceFile(
            "src/main/java/com/rakibulcodes/callerinfo/CallerLookupSource.kt"
        ).readText()

        assertTrue(source.contains("CONTACT"))
        assertTrue(source.contains("LOCAL"))
        assertTrue(source.contains("REMOTE"))
        assertFalse(entity.contains("CallerLookupSource"))
        assertFalse(entity.contains("NumberVerificationState"))
        assertTrue(preferences.contains("\"showLookupSource\""))
        assertFalse(preferences.contains("putString"))
    }

    @Test
    fun messageAccessAndLauncherChangesRemainAbsent() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()
        val gradle = sourceFile("build.gradle.kts").readText()

        assertFalse(manifest.contains("READ_SMS"))
        assertFalse(manifest.contains("Telephony.Sms"))
        assertTrue(gradle.contains("versionCode = 9"))
        assertTrue(gradle.contains("versionName = \"1.1.0-test.7\""))
    }

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app", relativePath).also {
            require(it.exists()) { "Missing test source: $relativePath" }
        }
    }

    private fun occurrences(value: String, search: String): Int =
        value.windowed(search.length).count { it == search }

    private val backupDomains = listOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref"
    )
}
