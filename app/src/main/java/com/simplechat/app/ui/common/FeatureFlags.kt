package com.simplechat.app.ui.common

/**
 * 语音相关功能的**总开关**。
 *
 * ### 当前状态
 *
 * - **朗读（TTS）已开启**：朗读用的是系统语音合成引擎 —— 不需要权限、不额外计费，
 *   音色由系统里装的 TTS 引擎决定（设置 → 朗读音色里可切换）；
 * - **语音输入仍然关闭**：识别那条链路的问题（上滑取消、松手发送、权限与键盘争抢）
 *   还没解决之前，不做"半个能用的麦克风"。
 *
 * ### 为什么是常量而不是设置项
 *
 * 这是"功能在不在"的开关，不是用户偏好：关掉期间界面里不该出现任何入口，
 * 也不该有半可用的状态。做成设置项反而会让"关着但能点"这种情况存在。
 *
 * ### 开启语音输入时要补什么
 *
 * 把 [VoiceInputEnabled] 改回 `true`，并把 `AndroidManifest.xml` 里的
 * `RECORD_AUDIO` 与识别服务的 `<queries>` 加回去 —— 少一样都会变成"点了没反应"。
 */
const val VoiceInputEnabled = false

/**
 * 朗读（系统 TTS）。
 *
 * 与 [VoiceInputEnabled] 相互独立：朗读只要系统里有 TTS 引擎就能用，
 * 不需要麦克风权限，所以它单独开着。
 */
const val ReadAloudEnabled = true
