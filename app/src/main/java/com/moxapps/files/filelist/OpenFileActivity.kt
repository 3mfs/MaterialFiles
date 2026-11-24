package com.moxapps.files.filelist

import android.content.Intent
import android.os.Bundle
import java8.nio.file.Path
import com.moxapps.files.app.AppActivity
import com.moxapps.files.app.application
import com.moxapps.files.file.MimeType
import com.moxapps.files.file.asMimeTypeOrNull
import com.moxapps.files.file.fileProviderUri
import com.moxapps.files.util.createViewIntent
import com.moxapps.files.util.extraPath
import com.moxapps.files.util.startActivitySafe

class OpenFileActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val path = intent.extraPath
        val mimeType = intent.type?.asMimeTypeOrNull()
        if (path != null && mimeType != null) {
            openFile(path, mimeType)
        }
        finish()
    }

    private fun openFile(path: Path, mimeType: MimeType) {
        val intent = path.fileProviderUri.createViewIntent(mimeType)
            .apply {
                if (!path.fileSystem.isReadOnly) {
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                extraPath = path
            }
        startActivitySafe(intent)
    }

    companion object {
        private const val ACTION_OPEN_FILE = "com.moxapps.files.intent.action.OPEN_FILE"

        fun createIntent(path: Path, mimeType: MimeType): Intent =
            Intent(ACTION_OPEN_FILE)
                .setPackage(application.packageName)
                .setType(mimeType.value)
                .apply { extraPath = path }
    }
}
