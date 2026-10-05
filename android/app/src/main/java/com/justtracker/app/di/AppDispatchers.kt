package com.justtracker.app.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatchers for the work that must leave the main thread (track lines, statistics, files). Passed in, so view model
 * and repository tests run it on the test scheduler (docs/05_architecture.md ADR-28).
 */
class AppDispatchers(
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
)
