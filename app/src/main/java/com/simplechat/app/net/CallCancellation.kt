package com.simplechat.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import okhttp3.Call

/** Close the socket when cancellation starts, even while execute/read is blocking. */
internal suspend fun Call.cancelWithCoroutine(): Job =
    CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@cancelWithCoroutine.cancel() }
    }
