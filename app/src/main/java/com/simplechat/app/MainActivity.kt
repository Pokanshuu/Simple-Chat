package com.simplechat.app

import android.Manifest
import android.content.ComponentName
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.data.AppLanguage
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.ui.RootScreen
import com.simplechat.app.ui.common.LocalUseSimpleCatIcon
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.ApplySystemBarAppearance
import com.simplechat.app.ui.theme.LocalAssistantFontFamily
import com.simplechat.app.ui.theme.LocalUserFontFamily
import com.simplechat.app.ui.theme.LocalFloatingBaseOpacity
import com.simplechat.app.data.ControlEffects
import com.simplechat.app.ui.theme.LocalAcrylicEnabled
import com.simplechat.app.ui.theme.LocalControlEffects
import com.simplechat.app.ui.theme.LocalPanelAccentOpacity
import com.simplechat.app.data.MaxPanelAccentOpacity
import com.simplechat.app.ui.theme.LocalWallpaper
import com.simplechat.app.ui.theme.MenuBackdropHost
import com.simplechat.app.ui.theme.WallpaperState
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.fontFamilyOf
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

            LaunchedEffect(current?.useSimpleCatIcon) {
                current?.let { updateLauncherIcon(it.useSimpleCatIcon) }
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

            /*
             * 三层字体各自解析：字体文件在私有目录里，theme 只关心"这一层用哪个
             * FontFamily"。文件被删掉 / 解析失败时 fontFamilyOf 返回 null，
             * 自动回退系统字体 —— 不会因为删个字体就白屏。
             *
             * 按 id 取而不是直接存 FontFamily：设置流更新后 key 变了才重算，
             * 否则每次重组都去碰文件。
             */
            val uiFont = remember(current?.uiFontId, current?.fonts) {
                current?.fonts?.firstOrNull { it.id == current.uiFontId }
                    ?.let { fontFamilyOf(this@MainActivity, it) }
            }
            val userFont = remember(current?.userFontId, current?.fonts) {
                current?.fonts?.firstOrNull { it.id == current.userFontId }
                    ?.let { fontFamilyOf(this@MainActivity, it) }
            }
            val assistantFont = remember(current?.assistantFontId, current?.fonts) {
                current?.fonts?.firstOrNull { it.id == current.assistantFontId }
                    ?.let { fontFamilyOf(this@MainActivity, it) }
            }
            /*
             * 壁纸状态整体打包成一个不可变对象：任何一个调节参数变了都要重新下发，
             * 所以 remember 的 key 用 `current` 里那几个字段的组合值。
             */
            val wallpaper = remember(
                current?.wallpaperPath,
                current?.wallpaperOpacity,
                current?.wallpaperBlur,
                current?.wallpaperBrightness,
                current?.wallpaperContrast,
                current?.wallpaperSaturation,
                current?.wallpaperOffsetX,
                current?.wallpaperOffsetY,
                current?.wallpaperScale,
                current?.wallpaperDim,
                current?.wallpaperFit,
                current?.wallpaperFlipH,
            ) {
                WallpaperState(
                    file = current?.wallpaperPath?.takeIf { it.isNotBlank() }
                        ?.let { com.simplechat.app.data.WallpaperFiles.fileOf(this@MainActivity, it) },
                    opacity = current?.wallpaperOpacity ?: 0.35f,
                    blur = current?.wallpaperBlur ?: 0f,
                    brightness = current?.wallpaperBrightness ?: 1f,
                    contrast = current?.wallpaperContrast ?: 1f,
                    saturation = current?.wallpaperSaturation ?: 1f,
                    offsetX = current?.wallpaperOffsetX ?: 0.5f,
                    offsetY = current?.wallpaperOffsetY ?: 0.5f,
                    scale = current?.wallpaperScale ?: 1f,
                    dim = current?.wallpaperDim ?: 0f,
                    fit = current?.wallpaperFit ?: "COVER",
                    flipH = current?.wallpaperFlipH ?: false,
                )
            }

            SimpleChatTheme(
                themeMode = themeMode,
                accent = accent,
                fontScale = fontScale,
                uiFontFamily = uiFont,
                typographySettings = current?.typography,
                typographyFonts = com.simplechat.app.ui.theme.TypographyFonts(uiFont, userFont, assistantFont),
            ) {
                ApplySystemBarAppearance()
                CompositionLocalProvider(
                    LocalUseSimpleCatIcon provides (current?.useSimpleCatIcon ?: true),
                    LocalUserFontFamily provides userFont,
                    LocalAssistantFontFamily provides assistantFont,
                    LocalWallpaper provides wallpaper,
                    LocalAcrylicEnabled provides (current?.acrylicEnabled == true),
                    LocalControlEffects provides (current?.controlEffects ?: ControlEffects()),
                    com.simplechat.app.ui.theme.LocalFloatingGlass provides (current?.floatingGlass ?: 0f),
                    LocalPanelAccentOpacity provides (
                        if (current?.panelAccentEnabled == true) current.panelAccentOpacity.coerceIn(0f, MaxPanelAccentOpacity)
                        else 0f
                    ),
                    LocalFloatingBaseOpacity provides (current?.wallpaperPanelAlpha ?: StoredSettings().wallpaperPanelAlpha),
                ) {
                    MenuBackdropHost { RootScreen() }
                }
            }
        }
    }

    internal fun applyLauncherIconAndExit(useSimpleCat: Boolean) {
        updateLauncherIcon(useSimpleCat)
        finishAndRemoveTask()
    }

    private fun updateLauncherIcon(useSimpleCat: Boolean) {
        val classic = ComponentName(packageName, "com.simplechat.app.ClassicLauncher")
        val cat = ComponentName(packageName, "com.simplechat.app.SimpleCatLauncher")
        val manager = packageManager
        val selected = if (useSimpleCat) cat else classic
        val unselected = if (useSimpleCat) classic else cat
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val default = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val flags = PackageManager.DONT_KILL_APP

        val changes = listOf(selected to enabled, unselected to disabled).filter { (component, target) ->
            val state = manager.getComponentEnabledSetting(component)
            state != target && !(state == default && (component == cat) == (target == enabled))
        }
        if (changes.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, flags)
            })
        } else {
            // Keep a launcher entry available while switching on older Android versions.
            changes.forEach { (component, state) ->
                manager.setComponentEnabledSetting(component, state, flags)
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
