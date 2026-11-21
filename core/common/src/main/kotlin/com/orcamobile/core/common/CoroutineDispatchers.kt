package com.orcamobile.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatchers the app runs work on, injected instead of referenced through
 * [kotlinx.coroutines.Dispatchers] so tests can substitute a test dispatcher.
 *
 * @property default CPU-bound work (geometry, parsing).
 * @property io blocking I/O and long native calls such as slicing.
 * @property main the UI thread.
 */
data class CoroutineDispatchers(
    val default: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val main: CoroutineDispatcher,
)
