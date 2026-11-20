package com.simplechat.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.data.AppLanguage
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.ui.RootScreen
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.ApplySystemBarAppearance
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // composable 的 `stringResource` 走这里的 resources，所以语言也得在这包一层。
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 诊断日志的第一条：从进程启动起算，排查时能看出"这一场"是什么时候开始的
        com.simplechat.app.data.Diagnostics.log(
            "APP",
            "onCreate ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "android=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )

        val settingsStore = (application as App).container.settingsStore
        val systemFontScale = resources.configuration.fontScale.coerceAtLeast(0.01f)

        setContent {
            RequestNotificationPermission()

            val stored by settingsStore.flow.collectAsStateWithLifecycle(
                initialValue = null,
            )

            val current = stored

            // 设置里换了语言：改完缓存整页重建，两个 context（App / Activity）重包一遍。
            // 首帧 `current` 还是 null（异步未到），那不是"语言变了"，不能重建。
            LaunchedEffect(current?.languageId) {
                if (current == null) return@LaunchedEffect
                val language = AppLanguage.fromId(current.languageId)
                if (language != LocaleHelper.currentLanguage(this@MainActivity)) {
                    LocaleHelper.onLanguageChanged(language)
                    recreate()
                }
            }

            val themeMode = runCatching {
                ThemeMode.valueOf(current?.themeModeId ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM)

            // 存的是枚举名；认不出来（比如回滚到旧版本后又被改坏）就回落到蓝色
            val accent = runCatching {
                AccentColor.valueOf(current?.accentColorId ?: AccentColor.BLUE.name)
            }.getOrDefault(AccentColor.BLUE)

            // 跟随系统时交给系统（倍率传 1 即"原样"）；不跟随时用用户值**替换**系统倍率，
            // 所以要先除掉系统那一层，否则会和系统设置相乘。
            val fontScale = when {
                current == null -> 1f
                current.followSystemFont -> 1f
                else -> current.fontScale / systemFontScale
            }

            SimpleChatTheme(
                themeMode = themeMode,
                accent = accent,
                fontScale = fontScale,
            ) {
                ApplySystemBarAppearance()
                RootScreen()
            }
        }
    }
}

/**
 * Android 13+ 的「正在生成回复」通知需要运行时权限。
 *
 * 清单里声明 `POST_NOTIFICATIONS` 只是"允许申请" —— 不申请就永远不弹，
 * 通知也永远不显示（前台服务照常运行，只是通知栏那片空白）。
 *
 * 冷启动问一次：拒绝状态由系统自己记（Android 13 起拒绝两次后不再弹），
 * 这里不再额外存"别再问了"的标记。被拒不影响后台生成本身。
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ -> }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
