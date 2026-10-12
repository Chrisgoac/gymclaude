# GymLog release rules. Libraries (kotlinx.serialization, Ktor, OkHttp, Clerk, Room, WorkManager,
# Play Billing, AndroidX) ship their own consumer rules; add a rule here only when a minified
# release build or its smoke test proves it is needed, with the reason next to it.

# No verbose/debug/info logging in release, even if a call slips past a BuildConfig.DEBUG guard.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
