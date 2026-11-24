package com.moxapps.files.provider.archive

import android.content.Context
import java8.nio.file.Path
import com.moxapps.files.fileaction.ArchivePasswordDialogActivity
import com.moxapps.files.fileaction.ArchivePasswordDialogFragment
import com.moxapps.files.provider.common.UserAction
import com.moxapps.files.provider.common.UserActionRequiredException
import com.moxapps.files.util.createIntent
import com.moxapps.files.util.putArgs
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class ArchivePasswordRequiredException(
    private val file: Path,
    reason: String?
) :
    UserActionRequiredException(file.toString(), null, reason) {

    override fun getUserAction(continuation: Continuation<Boolean>, context: Context): UserAction {
        return UserAction(
            ArchivePasswordDialogActivity::class.createIntent().putArgs(
                ArchivePasswordDialogFragment.Args(file) { continuation.resume(it) }
            ), ArchivePasswordDialogFragment.getTitle(context),
            ArchivePasswordDialogFragment.getMessage(file, context)
        )
    }
}
