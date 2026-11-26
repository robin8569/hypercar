package io.github.hypercar.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import io.github.hypercar.shizuku.ShizukuTools

/**
 * OPPO「Car+ 车联」入口探测（2026-11-23）。
 *
 * Car+ 车联与小米 CarWith 属于同一类 CarLife 手机端：自己建虚拟屏、自己编码，并在本机开
 * 7240/8240/9240/9340 等七通道；HyperCar 的 Rust 引擎仍以车机身份拨号接管，再把画面/声音
 * 转成 AirPlay。协议层不需要改动，差别只在“拉起谁、拉起它的哪个 Activity”。
 *
 * CarWith 有公开的 `castfwk.usb.CarlifeAdbActivity`，Car+ 车联没有公开文档。因此这里：
 *  1. 按 [PACKAGES] 顺序找已安装且启用的包；
 *  2. 读该包的 Activity 清单，按类名关键字打分（carlife > adb/debug > cast/screen/link）；
 *  3. 选最高分的导出 Activity 启动，全部候选写进诊断日志，便于真机确认；
 *  4. 设置里的 `包名/Activity` 可以覆盖自动选择。
 *
 * 探测不到“本机 CarLife 调试入口”时，只能拉起它的普通首页，此时 7240 不会监听、
 * 引擎会一直 ECONNREFUSED——日志里的 `CARPLUS:` 行就是用来区分这两种情况的。
 */
internal object CarPlusSource {

    /**
     * 已实机确认的硬化入口（ColorOS 17 / Car+ 17.32.x）：`com.oplus.ocar/.CarlifeActivity`。
     *
     * 上游 CarWith 之所以能单发，是因为入口是已知常量；这里把探测结果同样固化成常量，
     * 连接路径上默认只拉起这一次，等 7240 进入 LISTEN；失败才降级扫候选。
     */
    val HARDENED = ComponentName("com.oplus.ocar", "com.oplus.ocar.CarlifeActivity")

    /** 常见包名，按可能性排序（ColorOS 各版本命名不一致；com.oplus.ocar 为现行包名）。 */
    val PACKAGES = listOf(
        "com.oplus.ocar",
        "com.oplus.carplus",
        "com.coloros.carplus",
        "com.oplus.carlink",
        "com.coloros.carlink",
    )

    /** 类名强信号：命中即视为 CarLife 入口。 */
    private val STRONG = listOf("carlife")

    /** 次强信号：调试/直连/有线入口。 */
    private val MEDIUM = listOf("adb", "debug", "devtool", "wired", "usb", "projection", "session")

    /** 弱信号：投屏/连接相关。 */
    private val WEAK = listOf("cast", "project", "screen", "link", "connect")

    /** 反信号：引导页、下载页、关于页——能启动，但不会进 CarLife 会话。 */
    private val PENALTY = listOf(
        "guide", "download", "about", "help", "permission", "error", "fail",
        "intro", "splash", "web", "setting", "instruction", "tips", "notice",
        "agreement", "privacy",
    )

    data class Candidate(val className: String, val exported: Boolean, val score: Int)

    fun installedPackage(ctx: Context): String? = PACKAGES.firstOrNull { enabled(ctx, it) }

    fun enabled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun candidates(ctx: Context, pkg: String): List<Candidate> = runCatching {
        val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        (info.activities ?: return emptyList())
            .asSequence()
            .filter { it.name.isNotBlank() }
            .map { Candidate(it.name, it.exported, score(it)) }
            .sortedByDescending { it.score }
            .toList()
    }.getOrDefault(emptyList())

    private fun score(activity: ActivityInfo): Int {
        val name = activity.name.lowercase()
        var s = 0
        if (STRONG.any { name.contains(it) }) s += 100
        if (MEDIUM.any { name.contains(it) }) s += 40
        if (WEAK.any { name.contains(it) }) s += 15
        if (activity.exported) s += 20
        // 主投屏页优先：CarlifeActivity 命中即高于“已发现配件/有线引导”这类过渡页。
        if (name.endsWith("carlifeactivity")) s += 30
        // 引导/下载/关于页能启动但不开 CarLife 会话，压到候选尾部。
        if (PENALTY.any { name.contains(it) }) s -= 60
        // 普通首页/设置页优先级下调：它们能启动，但不会开 CarLife 端口。
        if (name.contains("launcher") || name.endsWith(".mainactivity") || name.contains("settings")) s -= 25
        return s
    }

    /**
     * 自动扫描顺序：导出且分数 > 0，按分数从高到低，最多 [limit] 个。
     *
     * 真机上“能启动”≠“进了 CarLife 会话”，所以调用方要逐个试，用 7240 是否 LISTEN 判定命中。
     */
    fun sweepList(ctx: Context, pkg: String, limit: Int = 5): List<Candidate> =
        candidates(ctx, pkg).filter { it.exported && it.score > 0 }.take(limit)

    /**
     * 7240 是否已在 LISTEN。读 `/proc/net/tcp`，**不**用 socket 去连——一次真实连接会被 CarLife 消耗掉。
     * Shizuku 未就绪时返回 null（未知，不等于否）。
     */
    suspend fun listeningOn7240(): Boolean? {
        if (!ShizukuTools.isReady) return null
        val result = runCatching {
            ShizukuTools.exec(
                """awk '$2 ~ /:1C48$/ && $4 == "0A" {c++} END {print c+0}' /proc/net/tcp /proc/net/tcp6""",
            )
        }.getOrNull() ?: return null
        return (result.out.trim().toIntOrNull() ?: 0) > 0
    }

    /** 硬化入口只在确实存在于该包清单里时才直接使用，避免给不存在的组件发 am start。 */
    fun hardened(ctx: Context, pkg: String): ComponentName? {
        if (pkg != HARDENED.packageName) return null
        val present = candidates(ctx, pkg).any { it.className == HARDENED.className }
        return if (present) HARDENED else null
    }

    /**
     * 轮询 7240 是否 LISTEN（只读 `/proc/net/tcp`，不消耗真实连接）：命中立刻返回，
     * 不做固定睡眠。返回 null 表示 Shizuku 未就绪、状态未知。
     */
    suspend fun awaitListening(
        timeoutMillis: Long = 3_000,
        stepMillis: Long = 300,
        alive: () -> Boolean = { true },
    ): Boolean? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val listening = listeningOn7240()
            if (listening == true || !alive() || System.currentTimeMillis() >= deadline) return listening
            kotlinx.coroutines.delay(stepMillis)
        }
    }

    /** 已安装的 CarLife / Car+ 相关包，用来判断“没有 CarLife 组件”还是“入口没选对”。 */
    suspend fun packageSnapshot(): String {
        if (!ShizukuTools.isReady) return "packages=unavailable(Shizuku 未就绪)"
        val result = runCatching {
            ShizukuTools.exec("pm list packages | grep -Ei 'carlife|carplus|ocar|carlink|iccoa'")
        }.getOrNull() ?: return "packages=unknown"
        val list = result.out.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()
        return if (list.isEmpty()) "packages=none" else "packages=" + list.joinToString(",")
    }

    /** 一行诊断：包名、版本、Activity 数与带分数的候选。 */
    fun describe(ctx: Context, pkg: String): String {
        val version = runCatching {
            val pi = ctx.packageManager.getPackageInfo(pkg, 0)
            "${pi.versionName}/${pi.longVersionCode}"
        }.getOrDefault("unknown")
        val list = candidates(ctx, pkg)
        val short = list.take(8).joinToString(",") { c ->
            "${c.className.substringAfterLast('.')}(exp=${c.exported},s=${c.score})"
        }
        return "pkg=$pkg version=$version activities=${list.size} [$short]"
    }

    /**
     * 解析要启动的组件：显式覆盖 > 最高分导出 Activity > 任意导出 Activity > 启动器入口。
     * 一个都没有时返回 null，由调用方把“没有可启动入口”写进日志。
     */
    fun resolve(ctx: Context, pkg: String, override: String?): ComponentName? {
        override?.trim()?.takeIf { it.contains('/') }?.let { raw ->
            val parts = raw.split('/', limit = 2)
            val p = parts[0].trim()
            var c = parts[1].trim()
            if (c.startsWith(".")) c = p + c
            if (p.isNotBlank() && c.isNotBlank()) return ComponentName(p, c)
        }
        val list = candidates(ctx, pkg)
        val best = list.firstOrNull { it.exported && it.score > 0 }
            ?: list.firstOrNull { it.exported }
        if (best != null) return ComponentName(pkg, best.className)
        return runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg)?.component }.getOrNull()
    }

    fun intentFor(component: ComponentName): Intent =
        Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
