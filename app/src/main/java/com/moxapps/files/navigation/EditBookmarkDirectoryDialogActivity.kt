package com.moxapps.files.navigation

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import com.moxapps.files.app.AppActivity
import com.moxapps.files.util.args
import com.moxapps.files.util.putArgs

class EditBookmarkDirectoryDialogActivity : AppActivity() {
    private val args by args<EditBookmarkDirectoryDialogFragment.Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = EditBookmarkDirectoryDialogFragment().putArgs(args)
            supportFragmentManager.commit {
                add(fragment, EditBookmarkDirectoryDialogFragment::class.java.name)
            }
        }
    }
}
