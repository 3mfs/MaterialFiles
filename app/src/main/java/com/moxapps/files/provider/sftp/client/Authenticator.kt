package com.moxapps.files.provider.sftp.client

interface Authenticator {
    fun getAuthentication(authority: Authority): Authentication?
}
