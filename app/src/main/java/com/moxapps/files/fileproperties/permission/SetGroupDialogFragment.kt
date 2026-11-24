package com.moxapps.files.fileproperties.permission

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import java8.nio.file.Path
import com.moxapps.files.R
import com.moxapps.files.file.FileItem
import com.moxapps.files.filejob.FileJobService
import com.moxapps.files.provider.common.PosixFileAttributes
import com.moxapps.files.provider.common.PosixGroup
import com.moxapps.files.provider.common.toByteString
import com.moxapps.files.util.SelectionLiveData
import com.moxapps.files.util.putArgs
import com.moxapps.files.util.show
import com.moxapps.files.util.viewModels

class SetGroupDialogFragment : SetPrincipalDialogFragment() {
    override val viewModel: SetPrincipalViewModel by viewModels { { SetGroupViewModel() } }

    @StringRes
    override val titleRes: Int = R.string.file_properties_permission_set_group_title

    override fun createAdapter(selectionLiveData: SelectionLiveData<Int>): PrincipalListAdapter =
        GroupListAdapter(selectionLiveData)

    override val PosixFileAttributes.principal
        get() = group()!!

    override fun setPrincipal(path: Path, principal: PrincipalItem, recursive: Boolean) {
        val group = PosixGroup(principal.id, principal.name?.toByteString())
        FileJobService.setGroup(path, group, recursive, requireContext())
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            SetGroupDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
