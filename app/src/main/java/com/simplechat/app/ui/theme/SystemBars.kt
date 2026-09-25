package com.simplechat.app.ui.theme

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext

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
 * ### 为什么放在 `SideEffect` 里，而不是 `onCreate` 调一次
 *
 * 主题在设置页随时可改，改完必须重新施加。`SideEffect` 每次重组都跑，`dark` 一变就重来一遍；
 * 它设的都是几个窗口属性，重设的代价可以忽略。
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

    SideEffect {
        val window = activity.window

        activity.enableEdgeToEdge(
            statusBarStyle = transparentBar(dark),
            navigationBarStyle = navigationBar(dark),
        )

        /*
         * 系统给导航栏补的「对比度底」。三键导航下它确实有用（不然三个键压在浅色
         * 正文上会看不见），但手势导航下它就是我们要去掉的那条带子 —— 而本 App 的
         * 底部永远铺着自己的底色，不存在"看不见"的问题。
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // MIUI 还会在导航栏上沿画一条 1px 的分割线，一并去掉
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
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
