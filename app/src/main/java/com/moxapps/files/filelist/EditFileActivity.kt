package com.moxapps.files.filelist

import android.os.Bundle
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import com.moxapps.files.app.AppActivity
import com.moxapps.files.file.MimeType
import com.moxapps.files.file.fileProviderUri
import com.moxapps.files.util.ParcelableArgs
import com.moxapps.files.util.ParcelableParceler
import com.moxapps.files.util.args
import com.moxapps.files.util.createEditIntent
import com.moxapps.files.util.startActivitySafe

// Use a trampoline activity so that we can have a proper icon and title.
class EditFileActivity : AppActivity() {
    private val args by args<Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivitySafe(args.path.fileProviderUri.createEditIntent(args.mimeType))
        finish()
    }

    @Parcelize
    class Args(
        val path: @WriteWith<ParcelableParceler> Path,
        val mimeType: MimeType
    ) : ParcelableArgs
}
