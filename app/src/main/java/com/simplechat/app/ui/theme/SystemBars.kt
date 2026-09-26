package com.simplechat.app.ui.theme

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 系统栏（状态栏 / 导航栏，含 MIUI · HyperOS 的「小白条」）跟着**本 App 的主题**走，
 * 并且**不许系统在导航栏后面自己补底**。
 *
 * ### 为什么需要额外做这件事
 *
 * `enableEdgeToEdge()` 只负责「让布局伸到系统栏后面」，剩下两件它按**系统**的来：
 *
 * 1. **系统栏图标的明暗**取自 `detectDarkMode(resources)`，也就是系统的深浅。
 *    本 App 的主题模式是独立的（设置页可强制浅色 / 深色），于是「系统浅色 + App 深色」
 *    时状态栏图标和「小白条」会反色 —— 深色底上一排看不见的图标。
 *
 * 2. **导航栏的对比度兜底**。targetSdk ≥ 35 的设备上走 AndroidX 的 `EdgeToEdgeApi35.setUp`，
 *    它执行 `window.isNavigationBarContrastEnforced = (nightMode == MODE_NIGHT_NO)`；
 *    而 `enableEdgeToEdge()` 默认的导航栏样式是 `SystemBarStyle.auto(浅遮罩, 深遮罩)`，
 *    其 `nightMode` 是 `MODE_NIGHT_AUTO`（= 0），于是这句被设成 **true** ——
 *    系统就**获准往导航栏后面加一层半透明遮罩**。手势导航下这正是那条
 *    「底部跟界面对不上」的带子，也就是金标联盟「导航条适配」里说的割裂感。
 *    官方文档也明说：要一个透明的手势导航栏，就把 `isNavigationBarContrastEnforced` 设成 false。
 *
 * 所以这里三件事：两个系统栏的样式都显式写成透明、把 `detectDarkMode` 换成 **App 的深浅**、
 * 再把系统那道兜底（以及 MIUI 额外画的一条分割线）关掉。
 *
 * ### 为什么放在 `SideEffect` + `ON_RESUME` 里，而不是 `onCreate` 调一次
 *
 * 主题在设置页随时可改，改完必须重新施加。`SideEffect` 每次重组都跑，`dark` 一变就重来一遍；
 * 它设的都是几个窗口属性，重设的代价可以忽略。
 *
 * `ON_RESUME` 那一遍是**竞态兜底**（真机：澎湃 OS3 上「换语言后状态栏不沉浸」）：
 * 换语言 = `recreate()`，系统会在重建 / resume 前后把窗口属性重设回它那套
 * （`decorFitsSystemWindows`、对比度兜底），而那次重设落在最后一次 `SideEffect`
 * **之后**，界面又是静止的设置页、没有下一次重组来纠正 —— 就停在"不沉浸"上；
 * 冷启动时序不同，退出重进反而正常。resume 是重建 / 切后台回来 / 跳出去再回来
 * 都必然经过的点，在它之后重施一遍就能赢下这场竞态，再补一帧 `decorView.post`
 * 兜住"重设发生在 resume 之后一拍"的时序。
 *
 * ### 布局侧的前提（这里不负责）
 *
 * 只把系统栏变透明还不够，**内容必须真的铺到它后面**：各页面根容器已经是
 * `fillMaxSize()` + 自己的底色（如 `ChatScreen` 的 `pageBackground`），
 * 底部再按 `navigationBarsPadding()` 主动避让。三条缺一不可。
 */
@Composable
fun ApplySystemBarAppearance() {
    val dark = LocalIsDarkTheme.current
    val activity = LocalContext.current as? ComponentActivity ?: return

    SideEffect { applySystemBarAppearance(activity, dark) }

    // 竞态兜底：resume 时重施一遍（机制见类注释「ON_RESUME 那一遍」）
    val latestDark by rememberUpdatedState(dark)
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applySystemBarAppearance(activity, latestDark)
                activity.window.decorView.post {
                    applySystemBarAppearance(activity, latestDark)
                }
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}

/** 施加系统栏样式。`SideEffect` 与 `ON_RESUME` 共用这一份，别让两处各写一遍。 */
private fun applySystemBarAppearance(activity: ComponentActivity, dark: Boolean) {
    val window = activity.window

    activity.enableEdgeToEdge(
        statusBarStyle = transparentBar(dark),
        navigationBarStyle = navigationBar(dark),
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        /*
         * 两个对比度兜底都关掉：系统给系统栏垫的「半透明底」正是肉眼看到的
         * 「不沉浸」。状态栏那道 `enableEdgeToEdge`（Api29 起）也会关，但系统
         * 可能在重建后把它设回去 —— 这里显式再写一遍，不依赖调用顺序。
         */
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    // MIUI 还会在导航栏上沿画一条 1px 的分割线，一并去掉
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.navigationBarDividerColor = Color.TRANSPARENT
    }
}

/** 状态栏：任何版本都透明（内容从它后面铺过去），明暗跟随 App 主题。 */
private fun transparentBar(dark: Boolean) = SystemBarStyle.auto(
    lightScrim = Color.TRANSPARENT,
    darkScrim = Color.TRANSPARENT,
    detectDarkMode = { dark },
)

/*
 * API 29 以下给导航栏垫的那层遮罩。
 *
 * 就是 AndroidX `EdgeToEdge` 里 `DefaultLightScrim` / `DefaultDarkScrim` 的值 ——
 * 那两个常量是 private，只能照抄（`Color.argb(0xe6, 0xFF,0xFF,0xFF)` / `Color.argb(0x80, 0x1b,0x1b,0x1b)`）。
 */
private const val LegacyLightScrim = 0xE6FFFFFF.toInt()
private const val LegacyDarkScrim = 0x801B1B1B.toInt()

/**
 * 导航栏。
 *
 * API 29 起一律透明 —— 那时系统栏本来就在内容后面。
 *
 * API 29 以下（minSdk 26）**保留上面那层遮罩**：那时的三键导航键是画在导航栏底色上的，
 * 全透明会让它在浅色页面里彻底消失。这层遮罩只在这个区间存在，不影响新系统上的沉浸效果。
 */
private fun navigationBar(dark: Boolean): SystemBarStyle =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        transparentBar(dark)
    } else {
        SystemBarStyle.auto(LegacyLightScrim, LegacyDarkScrim, detectDarkMode = { dark })
    }
