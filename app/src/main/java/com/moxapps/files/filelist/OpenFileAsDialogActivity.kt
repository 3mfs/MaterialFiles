package com.moxapps.files.filelist

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import com.moxapps.files.app.AppActivity
import com.moxapps.files.util.args
import com.moxapps.files.util.putArgs

class OpenFileAsDialogActivity : AppActivity() {
    private val args by args<OpenFileAsDialogFragment.Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = OpenFileAsDialogFragment().putArgs(args)
            supportFragmentManager.commit {
                add(fragment, OpenFileAsDialogFragment::class.java.name)
            }
        }
    }
}
