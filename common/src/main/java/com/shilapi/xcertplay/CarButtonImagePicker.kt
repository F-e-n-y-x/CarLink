package com.shilapi.xcertplay

import android.content.ActivityNotFoundException

/** Prefer file locations; older head units may only provide a content picker. */
internal fun launchCarButtonImagePicker(
    openDocument: () -> Unit,
    getContent: () -> Unit,
): Result<Unit> = runCatching {
    try {
        openDocument()
    } catch (_: ActivityNotFoundException) {
        getContent()
    }
}
