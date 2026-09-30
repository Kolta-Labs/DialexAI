package com.dialex.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

/** The one place every UI-triggered call into [EngineViewModel] should go through instead of
 * a bare `scope.launch { ... }` — catches anything [block] throws (a network hiccup, an
 * engine-side error, anything) and records it in [EngineViewModel.lastError] for the UI to
 * show as a dismissible banner, instead of letting it reach the coroutine scope's own
 * uncaught-exception path, which on both desktop and Android tears down the whole app. A
 * [kotlinx.coroutines.CancellationException] is deliberately let through unchanged — that's
 * normal structured-concurrency cancellation (e.g. the screen was left), not a failure to
 * report to the user. Belt-and-suspenders: also installs a [CoroutineExceptionHandler] on the
 * launched coroutine itself, in case something throws *after* an await point in a way that
 * skips the try/catch (a child coroutine's own uncaught failure, for instance). */
fun CoroutineScope.launchGuarded(vm: EngineViewModel, block: suspend () -> Unit) {
    val handler = CoroutineExceptionHandler { _, throwable ->
        vm.reportError(throwable.message ?: throwable.toString())
    }
    launch(handler) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            vm.reportError(t.message ?: t.toString())
        }
    }
}
