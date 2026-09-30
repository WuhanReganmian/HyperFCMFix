package org.hermes.hyperfcmfix

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModernHook : XposedModule() {
    companion object {
        private const val TAG = "HyperFCMFix"
        private const val GMS_PKG = "com.google.android.gms"
        private const val MODULE_PKG = "org.hermes.hyperfcmfix"
        private const val SETTING_MILLET = "MILLET_NO_RESTRICT_APP"
        private const val OP_AUTO_START = 10008
        private const val OP_FCM_BROADCAST = 11
        private const val MODE_ALLOWED = 0

        private const val FCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
        private const val FCM_REGISTRATION = "com.google.android.c2dm.intent.REGISTRATION"
        private const val FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES = 0x00000020
        private const val FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES = 0x00000010

        private const val INITIAL_DELAY_MS = 5 * 60 * 1000L
        private const val REPAIR_PERIOD_MS = 12 * 60 * 60 * 1000L

        @Volatile private var fcmPackages: Set<String> = emptySet()
        @Volatile private var systemContext: Context? = null
        @Volatile private var systemServerClassLoader: ClassLoader? = null
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "HyperFCMFix-Repair").apply { isDaemon = true }
        }
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        private fun isFcmAction(action: String?) =
            action == FCM_RECEIVE || action == FCM_REGISTRATION

        private fun logEvent(context: Context?, message: String, level: Int = Log.INFO) {
            Log.println(level, TAG, message)
            try {
                if (context == null) return
                val appContext = context.createPackageContext(
                    MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY
                )
                val file = File(appContext.filesDir, "hyperfcmfix.log")
                file.parentFile?.mkdirs()
                synchronized(dateFormat) {
                    file.appendText("${dateFormat.format(Date())} $message\n")
                }
                if (file.length() > 1024 * 1024) {
                    val lines = file.readLines()
                    file.writeText(lines.takeLast(5000).joinToString("\n") + "\n")
                }
            } catch (_: Throwable) {
                // Logging must never affect system_server.
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        systemServerClassLoader = classLoader
        log(Log.INFO, TAG, "HyperFCMFix starting")
        hookDeviceIdleController(classLoader)
        hookAppOpsService(classLoader)
        hookBroadcastQueue(classLoader)
        hookXiaomiBroadcastStub(classLoader)
        hookSystemReady(classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) = Unit

    private fun hookDeviceIdleController(classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.android.server.DeviceIdleController")
            for (m in clazz.declaredMethods) {
                if (m.returnType == Boolean::class.javaPrimitiveType &&
                    (m.name == "isPowerSaveWhitelistApp" ||
                     m.name == "isPowerSaveWhitelistExceptIdleApp")) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val pkg = chain.args.firstOrNull() as? String
                        if (pkg == GMS_PKG) true else chain.proceed()
                    }
                }
            }
            log(Log.INFO, TAG, "DeviceIdleController GMS protection enabled")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "DeviceIdleController hook failed: ${t.message}")
        }
    }

    /**
     * Only GMS keeps its own autostart permission. We do NOT globally allow
     * OP_AUTO_START for every application anymore.
     */
    private fun hookAppOpsService(classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.android.server.appop.AppOpsService")
            for (m in clazz.declaredMethods) {
                if (m.returnType == Int::class.javaPrimitiveType &&
                    (m.name == "checkOperation" || m.name == "noteOperation")) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val code = chain.args.getOrNull(0) as? Int
                        val pkg = chain.args.getOrNull(2) as? String
                        if (pkg == GMS_PKG && (code == OP_AUTO_START || code == OP_FCM_BROADCAST)) {
                            MODE_ALLOWED
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
            log(Log.INFO, TAG, "AppOps restricted to GMS; no global AUTO_START bypass")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "AppOpsService hook failed: ${t.message}")
        }
    }

    /**
     * FCM-only stopped-app bypass. The target package must be in the
     * dynamically discovered FCM package set.
     */
    private fun hookBroadcastQueue(classLoader: ClassLoader) {
        val targets = listOf(
            "com.android.server.am.BroadcastQueueModernImpl" to "enqueueBroadcastLocked",
            "com.android.server.am.BroadcastController" to "broadcastIntentLocked"
        )
        for ((className, methodName) in targets) {
            try {
                val clazz = classLoader.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name != methodName) continue
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        var intent: Intent? = null
                        for (arg in chain.args) {
                            if (arg is Intent) { intent = arg; break }
                        }
                        val fcm = intent != null && isFcmAction(intent!!.action)
                        if (!fcm) {
                            chain.proceed()
                        } else {
                            val targetPackage = extractTargetPackage(chain.args)
                            val gmsCaller = chain.args.any {
                                it == GMS_PKG || it?.toString()?.contains(GMS_PKG) == true
                            }
                            val isTargetFcm = (targetPackage != null && fcmPackages.contains(targetPackage)) ||
                                targetPackage == GMS_PKG || gmsCaller

                            if (isTargetFcm) {
                            intent!!.flags = (intent!!.flags or FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES) and
                                FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES.inv()
                            for (i in chain.args.indices) {
                                if (chain.args[i] is Int && chain.args[i] == -1) {
                                    chain.args[i] = OP_FCM_BROADCAST
                                    break
                                }
                            }
                                logEvent(systemContext, "FCM broadcast bypass: action=${intent!!.action} target=${targetPackage ?: "unknown"}")
                            }
                            chain.proceed()
                        }
                    }
                }
                log(Log.INFO, TAG, "Hooked $className.$methodName")
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "Hook $className.$methodName failed: ${t.message}")
            }
        }
    }

    /**
     * Xiaomi's dispatch stop check is bypassed only when the invocation is
     * clearly associated with GMS/FCM or a known FCM package.
     */
    private fun hookXiaomiBroadcastStub(classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl")
            for (m in clazz.declaredMethods) {
                if (m.name != "shouldStopBroadcastDispatch" ||
                    m.returnType != Boolean::class.javaPrimitiveType) continue
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val text = chain.args.joinToString(" ") { it?.toString() ?: "" }
                    val isFcm = text.contains(FCM_RECEIVE) ||
                        text.contains(FCM_REGISTRATION) ||
                        text.contains(GMS_PKG) ||
                        fcmPackages.any { pkg -> text.contains(pkg) }
                    if (isFcm) {
                        logEvent(systemContext, "Greezer dispatch bypass: $text")
                        false
                    } else chain.proceed()
                }
                log(Log.INFO, TAG, "Hooked Xiaomi shouldStopBroadcastDispatch")
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Xiaomi BroadcastQueue hook unavailable: ${t.message}")
        }
    }

    private fun hookSystemReady(classLoader: ClassLoader) {
        try {
            val amsClass = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            for (m in amsClass.declaredMethods) {
                if (m.name != "systemReady") continue
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    val ams = chain.thisObject
                    Thread {
                        try {
                            val field = ams.javaClass.getDeclaredField("mContext")
                            field.isAccessible = true
                            val context = field.get(ams) as? Context ?: return@Thread
                            systemContext = context
                            logEvent(context, "systemReady: scheduling FCM scan/repair in 5 minutes")
                            scheduler.scheduleAtFixedRate(
                                { runRepair(context, "boot+5m / every+12h") },
                                INITIAL_DELAY_MS,
                                REPAIR_PERIOD_MS,
                                TimeUnit.MILLISECONDS
                            )
                            executePrivilegedCommands(context)
                            registerMilletObserver(context.contentResolver)
                        } catch (e: Throwable) {
                            logEvent(systemContext, "systemReady worker failed: ${e.message}", Log.WARN)
                        }
                    }.apply { isDaemon = true; name = "HyperFCMFix-Init"; start() }
                    result
                }
                break
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "systemReady hook failed: ${t.message}")
        }
    }

    private fun runRepair(context: Context, reason: String) {
        try {
            val packages = discoverFcmPackages(context)
            fcmPackages = packages
            saveFcmPackages(context, packages)
            injectGreezerWhiteList(context, packages)
            resetFcmBatteryOptimization(context, packages)
            ensureGmsInMillet(context.contentResolver)
            logEvent(context, "Repair complete: reason=$reason fcmApps=${packages.size}")
        } catch (t: Throwable) {
            logEvent(context, "Repair failed: ${t.message}", Log.WARN)
        }
    }

    /**
     * Finds applications declaring the classic FCM c2dm receiver.
     * GMS itself is intentionally excluded from the FCM-app battery reset.
     */
    private fun discoverFcmPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val result = linkedSetOf<String>()
        val intent = Intent(FCM_RECEIVE)
        val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        try {
            pm.queryBroadcastReceivers(intent, flags).forEach {
                val pkg = it.activityInfo?.packageName
                if (!pkg.isNullOrBlank() && pkg != GMS_PKG && pkg != MODULE_PKG &&
                    hasNotificationPermission(pm, pkg)) {
                    result.add(pkg)
                }
            }
        } catch (t: Throwable) {
            logEvent(context, "FCM receiver discovery failed: ${t.message}", Log.WARN)
        }
        return result
    }

    /**
     * Only applications that currently have the Android notification runtime
     * permission are treated as FCM apps. Declaring POST_NOTIFICATIONS in the
     * manifest is not enough: the user must have granted it.
     *
     * On Android versions before 13 the permission is a normal/install-time
     * permission, so checkPermission() remains the appropriate compatibility
     * check.
     */
    private fun hasNotificationPermission(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS, packageName) ==
                PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            logEvent(systemContext, "Notification permission check failed: $packageName ${t.message}", Log.WARN)
            false
        }
    }

    /**
     * "Optimized" in AOSP terms means the app is NOT on the deviceidle
     * power-save whitelist. We remove FCM apps from that whitelist.
     *
     * This deliberately does not touch HyperOS Autostart.
     */
    private fun resetFcmBatteryOptimization(context: Context, packages: Set<String>) {
        for (pkg in packages) {
            try {
                val cmd = "cmd deviceidle whitelist -$pkg"
                val exit = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
                logEvent(context, "Battery optimization -> Optimized: $pkg exit=$exit")
            } catch (t: Throwable) {
                logEvent(context, "Battery reset failed: $pkg ${t.message}", Log.WARN)
            }
        }
    }

    private fun injectGreezerWhiteList(context: Context, packages: Set<String>) {
        try {
            val clazz = systemServerClassLoader?.loadClass("com.miui.server.greeze.GreezeManagerService") ?: return
            for (field in clazz.declaredFields) {
                if (field.name != "mBroadcastTargetWhiteList") continue
                field.isAccessible = true
                val map = field.get(null) as? MutableMap<Any?, Any?> ?: continue
                val actions = mutableListOf(FCM_RECEIVE, FCM_REGISTRATION)
                for (pkg in packages) map[pkg] = actions
                map[GMS_PKG] = actions
                logEvent(context, "Greezer FCM whitelist refreshed: ${packages.size} apps")
            }
        } catch (t: Throwable) {
            logEvent(context, "Greezer whitelist refresh failed: ${t.message}", Log.WARN)
        }
    }

    private fun executePrivilegedCommands(context: Context) {
        val commands = listOf(
            "cmd deviceidle whitelist +$GMS_PKG",
            "cmd appops set $GMS_PKG AUTO_START allow",
            "cmd appops set $GMS_PKG BOOT_COMPLETED allow",
            "dumpsys greezer IM GMS disable",
            "dumpsys greezer LM add $GMS_PKG"
        )
        for (cmd in commands) {
            try {
                val exit = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
                logEvent(context, "Init command: [$cmd] exit=$exit")
            } catch (t: Throwable) {
                logEvent(context, "Init command failed: [$cmd] ${t.message}", Log.WARN)
            }
        }
    }

    private fun registerMilletObserver(resolver: ContentResolver) {
        try {
            ensureGmsInMillet(resolver)
            val uri = Settings.System.getUriFor(SETTING_MILLET)
            resolver.registerContentObserver(uri, false, object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    ensureGmsInMillet(resolver)
                }
            })
            log(Log.INFO, TAG, "MILLET observer registered")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "MILLET observer failed: ${e.message}")
        }
    }

    private fun ensureGmsInMillet(resolver: ContentResolver) {
        try {
            val current = Settings.System.getString(resolver, SETTING_MILLET) ?: ""
            val list = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!list.contains(GMS_PKG)) {
                list.add(GMS_PKG)
                Settings.System.putString(resolver, SETTING_MILLET, list.joinToString(","))
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "ensure MILLET failed: ${e.message}")
        }
    }

    private fun extractTargetPackage(args: Array<out Any?>): String? {
        for (arg in args) {
            when (arg) {
                is String -> if (arg.contains('.') && fcmPackages.contains(arg)) return arg
                is Intent -> arg.component?.packageName?.let { return it }
            }
        }
        return null
    }

    private fun saveFcmPackages(context: Context, packages: Set<String>) {
        try {
            val appContext = context.createPackageContext(MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY)
            File(appContext.filesDir, "fcm_apps.txt").writeText(packages.sorted().joinToString("\n"))
        } catch (_: Throwable) {}
    }
}
