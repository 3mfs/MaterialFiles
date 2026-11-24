package com.moxapps.files.provider.webdav.client

interface Authenticator {
    fun getAuthentication(authority: Authority): Authentication?
}
