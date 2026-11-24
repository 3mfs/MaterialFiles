package com.moxapps.files.app

import android.os.AsyncTask
import android.os.Build
import android.webkit.WebView
import jcifs.context.SingletonContext
import com.moxapps.files.BuildConfig
import com.moxapps.files.coil.initializeCoil
import com.moxapps.files.filejob.fileJobNotificationTemplate
import com.moxapps.files.ftpserver.ftpServerServiceNotificationTemplate
import com.moxapps.files.hiddenapi.HiddenApi
import com.moxapps.files.provider.FileSystemProviders
import com.moxapps.files.settings.Settings
import com.moxapps.files.storage.FtpServerAuthenticator
import com.moxapps.files.storage.SftpServerAuthenticator
import com.moxapps.files.storage.SmbServerAuthenticator
import com.moxapps.files.storage.StorageVolumeListLiveData
import com.moxapps.files.storage.WebDavServerAuthenticator
import com.moxapps.files.theme.custom.CustomThemeHelper
import com.moxapps.files.theme.night.NightModeHelper
import java.util.Properties
import com.moxapps.files.provider.ftp.client.Client as FtpClient
import com.moxapps.files.provider.sftp.client.Client as SftpClient
import com.moxapps.files.provider.smb.client.Client as SmbClient
import com.moxapps.files.provider.webdav.client.Client as WebDavClient

val appInitializers = listOf(
    ::initializeFirebase,
    ::disableHiddenApiChecks,
    ::initializeWebViewDebugging,
    ::initializeCoil,
    ::initializeFileSystemProviders,
    ::upgradeApp,
    ::initializeLiveDataObjects,
    ::initializeCustomTheme,
    ::initializeNightMode,
    ::createNotificationChannels
)

private fun initializeFirebase() {
//#ifdef NONFREE
    com.moxapps.files.nonfree.FirebaseInitializer.initialize()
//#endif
}

private fun disableHiddenApiChecks() {
    HiddenApi.disableHiddenApiChecks()
}

private fun initializeWebViewDebugging() {
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

private fun initializeFileSystemProviders() {
    FileSystemProviders.install()
    FileSystemProviders.overflowWatchEvents = true
    // SingletonContext.init() calls NameServiceClientImpl.initCache() which connects to network.
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
        SingletonContext.init(
            Properties().apply {
                setProperty("jcifs.netbios.cachePolicy", "0")
                setProperty("jcifs.smb.client.maxVersion", "SMB1")
            }
        )
    }
    FtpClient.authenticator = FtpServerAuthenticator
    SftpClient.authenticator = SftpServerAuthenticator
    SmbClient.authenticator = SmbServerAuthenticator
    WebDavClient.authenticator = WebDavServerAuthenticator
}

private fun initializeLiveDataObjects() {
    // Force initialization of LiveData objects so that it won't happen on a background thread.
    StorageVolumeListLiveData.value
    Settings.FILE_LIST_DEFAULT_DIRECTORY.value
}

private fun initializeCustomTheme() {
    CustomThemeHelper.initialize(application)
}

private fun initializeNightMode() {
    NightModeHelper.initialize(application)
}

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannels(
            listOf(
                backgroundActivityStartNotificationTemplate.channelTemplate,
                fileJobNotificationTemplate.channelTemplate,
                ftpServerServiceNotificationTemplate.channelTemplate
            ).map { it.create(application) }
        )
    }
}
