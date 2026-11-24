package com.moxapps.files.storage

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import com.moxapps.files.app.AppActivity
import com.moxapps.files.util.args
import com.moxapps.files.util.putArgs

class EditFtpServerActivity : AppActivity() {
    private val args by args<EditFtpServerFragment.Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = EditFtpServerFragment().putArgs(args)
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        }
    }
}
