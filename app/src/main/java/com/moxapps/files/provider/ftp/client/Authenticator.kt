package com.moxapps.files.provider.ftp.client

interface Authenticator {
    fun getPassword(authority: Authority): String?
}
