package com.moxapps.files.viewer.model

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import com.moxapps.files.app.AppActivity
import com.moxapps.files.util.putArgs

class ModelViewerActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = ModelViewerFragment()
                .putArgs(ModelViewerFragment.Args(intent))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        }
    }
}
