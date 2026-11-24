package com.moxapps.files.fileproperties.permission

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import java8.nio.file.Path
import com.moxapps.files.R
import com.moxapps.files.file.FileItem
import com.moxapps.files.filejob.FileJobService
import com.moxapps.files.provider.common.PosixFileAttributes
import com.moxapps.files.provider.common.PosixPrincipal
import com.moxapps.files.provider.common.PosixUser
import com.moxapps.files.provider.common.toByteString
import com.moxapps.files.util.SelectionLiveData
import com.moxapps.files.util.putArgs
import com.moxapps.files.util.show
import com.moxapps.files.util.viewModels

class SetOwnerDialogFragment : SetPrincipalDialogFragment() {
    override val viewModel: SetPrincipalViewModel by viewModels { { SetOwnerViewModel() } }

    @StringRes
    override val titleRes: Int = R.string.file_properties_permission_set_owner_title

    override fun createAdapter(selectionLiveData: SelectionLiveData<Int>): PrincipalListAdapter =
        UserListAdapter(selectionLiveData)

    override val PosixFileAttributes.principal: PosixPrincipal
        get() = owner()!!

    override fun setPrincipal(path: Path, principal: PrincipalItem, recursive: Boolean) {
        val owner = PosixUser(principal.id, principal.name?.toByteString())
        FileJobService.setOwner(path, owner, recursive, requireContext())
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            SetOwnerDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
