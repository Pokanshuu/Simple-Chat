package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.service.GenerationService

/**
 * 「后台继续生成」的接线。
 *
 * 这是**默认行为，不是一个选项**：用户在等长回复时回个消息、锁个屏，
 * 回来发现回复停在一半，只会被理解成"这软件又坏了"。
 *
 * 放在数据层做成一个**单例**，是为了让 `ChatViewModel` 不必持有 Context：
 * 它只需要在流开始 / 结束时说一句"现在在生成"或"生成完了"，
 * 至于要不要起服务、起哪种服务，都由这里决定。
 */
object BackgroundGeneration {

    private var appContext: Context? = null

    /** 是否已经起过服务（避免每次 token 都调一次 startService）。 */
    private var running = false

    /** 在 `App.onCreate` 里调用一次。 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 同步当前状态。[generating] = 是否正在生成回复。
     *
     * 幂等：状态没变就什么都不做，所以可以放心地在流的每次状态变化时调用。
     */
    fun sync(generating: Boolean) {
        val context = appContext ?: return
        if (generating == running) return
        running = generating
        if (generating) GenerationService.start(context) else GenerationService.stop(context)
    }
}
