package com.dialex.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A lightweight, caller-owned back stack implementation for KMP.
 * Replaces platform-specific Navigation 3 APIs with a pure Kotlin/Compose SnapshotStateList.
 */
class AppBackStack(val entries: SnapshotStateList<AppRoute>) {
    val current: AppRoute get() = entries.last()
    val size: Int get() = entries.size

    /** Indicates whether the last navigation mutation was a backward pop. */
    var isNavigatingBack: Boolean by mutableStateOf(false)
        private set

    fun add(route: AppRoute) {
        isNavigatingBack = false
        entries.add(route)
    }

    fun removeLast(): AppRoute {
        isNavigatingBack = true
        return if (entries.size > 1) {
            entries.removeAt(entries.size - 1)
        } else {
            entries.last()
        }
    }

    fun remove(route: AppRoute) {
        if (entries.size > 1) {
            isNavigatingBack = true
            entries.remove(route)
        }
    }

    fun removeAt(index: Int) {
        if (entries.size > 1 && index in entries.indices) {
            isNavigatingBack = true
            entries.removeAt(index)
        }
    }

    fun replaceTop(route: AppRoute) {
        isNavigatingBack = false
        if (entries.isNotEmpty()) {
            entries[entries.lastIndex] = route
        } else {
            entries.add(route)
        }
    }

    fun popTo(predicate: (AppRoute) -> Boolean): Boolean {
        val targetIndex = entries.indexOfLast(predicate)
        if (targetIndex >= 0) {
            isNavigatingBack = true
            while (entries.size > targetIndex + 1) {
                entries.removeAt(entries.size - 1)
            }
            return true
        }
        return false
    }

    fun popToRoot() {
        if (entries.size > 1) {
            isNavigatingBack = true
            val root = entries.first()
            entries.clear()
            entries.add(root)
        }
    }

    fun set(routes: List<AppRoute>) {
        if (routes.isNotEmpty()) {
            isNavigatingBack = false
            entries.clear()
            entries.addAll(routes)
        }
    }
}

@Composable
fun rememberAppBackStack(initialRoute: AppRoute): AppBackStack {
    val stateList = remember { mutableStateListOf(initialRoute) }
    return remember(stateList) { AppBackStack(stateList) }
}
