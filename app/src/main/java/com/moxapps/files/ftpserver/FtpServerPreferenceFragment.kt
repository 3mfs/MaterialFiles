package com.moxapps.files.ftpserver

import android.os.Bundle
import com.moxapps.files.R
import com.moxapps.files.ui.PreferenceFragmentCompat

class FtpServerPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.ftp_server)
    }
}
