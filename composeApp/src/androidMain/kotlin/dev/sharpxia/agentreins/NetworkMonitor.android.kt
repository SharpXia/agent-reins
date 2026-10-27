package dev.sharpxia.agentreins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val alwaysAvailable = MutableStateFlow(true)

internal actual val networkAvailable: StateFlow<Boolean> = alwaysAvailable
