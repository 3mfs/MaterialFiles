package me.zhanghai.android.files.fileproperties.permission

import androidx.annotation.DrawableRes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.SelectionLiveData

class UserListAdapter(
    selectionLiveData: SelectionLiveData<Int>
) : PrincipalListAdapter(selectionLiveData) {
    @DrawableRes
    override val principalIconRes: Int = R.drawable.person_icon_control_normal_24dp
}
