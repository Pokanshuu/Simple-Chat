package com.simplechat.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * Android 8 起安装未知来源应用是**每个应用单独授权**的：没授权时
 * `startActivity(ACTION_VIEW)` 不会报错，只是静默什么都不发生 —— 用户会以为
 * 按钮坏了。所以先查 `canRequestPackageInstalls()`，没授权就把人送到系统开关页，
 * 回来再点一次即可。
 *
 * 用 FileProvider 而不是 `file://`：Android 7+ 的 StrictMode 会直接抛
 * `FileUriExposedException`。
 */
object UpdateInstaller {

    /** 系统里是否已经允许本应用安装 APK。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 打开「安装未知应用」的授权页。 */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 拉起系统安装器。
     *
     * 调用前请先确认 [canInstall]；这里再兜一层，没权限就返回 false 而不是抛异常。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists()) return false
        if (!canInstall(context)) {
            openInstallPermission(context)
            return false
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
