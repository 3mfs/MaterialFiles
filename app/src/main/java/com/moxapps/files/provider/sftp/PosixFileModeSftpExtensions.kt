package com.moxapps.files.provider.sftp

import com.moxapps.files.provider.common.PosixFileModeBit
import com.moxapps.files.provider.common.toInt
import net.schmizz.sshj.sftp.FileAttributes

fun Set<PosixFileModeBit>.toSftpAttributes(): FileAttributes =
    FileAttributes.Builder().withPermissions(toInt()).build()
