package app.lawnchair.util

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun View.repeatOnAttached(block: suspend CoroutineScope.() -> Unit) {
    var launchedJob: Job? = null

    val mutex = Mutex()
    observeAttachedState { isAttached ->
        if (isAttached) {
            val lifecycleOwner = findViewTreeLifecycleOwner()
            launchedJob = if (lifecycleOwner != null) {
                lifecycleOwner.lifecycleScope.launch(
                    context = Dispatchers.Main.immediate,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        mutex.withLock {
                            coroutineScope {
                                block()
                            }
                        }
                    }
                }
            } else {
                MainScope().launch(
                    context = Dispatchers.Main.immediate,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    mutex.withLock {
                        coroutineScope {
                            block()
                        }
                    }
                }
            }
            return@observeAttachedState
        }
        launchedJob?.cancel()
        launchedJob = null
    }
}
