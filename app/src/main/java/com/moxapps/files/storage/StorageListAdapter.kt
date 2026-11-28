package com.moxapps.files.storage

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import com.moxapps.files.R
import com.moxapps.files.compat.foregroundCompat
import com.moxapps.files.compat.isTransformedTouchPointInViewCompat
import com.moxapps.files.databinding.StorageItemBinding
import com.moxapps.files.file.asFileSize
import com.moxapps.files.ui.SimpleAdapter
import com.moxapps.files.util.dpToDimensionPixelSize
import com.moxapps.files.util.layoutInflater

class StorageListAdapter(
    private val listener: Listener
) : SimpleAdapter<Storage, StorageListAdapter.ViewHolder>(),
    DraggableItemAdapter<StorageListAdapter.ViewHolder> {

    private var usageByStorageId: Map<Long, StorageUsageInfo> = emptyMap()

    fun setUsageMap(usageMap: Map<Long, StorageUsageInfo>) {
        usageByStorageId = usageMap
        notifyDataSetChanged()
    }

    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            StorageItemBinding.inflate(parent.context.layoutInflater, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val storage = getItem(position)
        val binding = holder.binding
        val context = binding.root.context
        val brandColor = StorageUsage.getBrandColor(storage, context)

        // Need to remove the ripple before it's drawn onto the bitmap for dragging.
        binding.root.foregroundCompat?.mutate()?.setVisible(!holder.dragState.isActive, false)
        binding.root.setOnClickListener { listener.editStorage(storage) }

        val iconBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpToDimensionPixelSize(10).toFloat()
            setColor(ColorUtils.setAlphaComponent(brandColor, 36))
        }
        binding.iconContainer.background = iconBg
        binding.iconImage.setImageResource(storage.iconRes)
        binding.iconImage.imageTintList = ColorStateList.valueOf(brandColor)

        binding.nameText.isActivated = storage.isVisible
        binding.nameText.text = storage.getName(context)
        binding.descriptionText.text = storage.description

        val statusColor = if (storage.isVisible) {
            if (StorageUsage.isRemoteStorage(storage)) brandColor else Color.parseColor("#10B981")
        } else {
            Color.parseColor("#F59E0B")
        }
        binding.statusBadgeText.text = StorageUsage.getStatusLabel(storage, context)
        binding.statusBadgeText.setTextColor(statusColor)
        binding.statusBadgeText.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpToDimensionPixelSize(8).toFloat()
            setColor(ColorUtils.setAlphaComponent(statusColor, 32))
        }

        val usage = usageByStorageId[storage.id]
        if (usage != null && usage.totalBytes > 0L) {
            binding.usageContainer.isVisible = true
            binding.usageProgress.setIndicatorColor(brandColor)
            binding.usageProgress.trackColor = ColorUtils.setAlphaComponent(brandColor, 45)
            binding.usageProgress.progress = usage.usedPercent
            val usedStr = usage.usedBytes.asFileSize().formatHumanReadable(context)
            val totalStr = usage.totalBytes.asFileSize().formatHumanReadable(context)
            binding.usageText.text = context.getString(
                R.string.storage_usage_of_total_format, usedStr, totalStr
            )
            binding.usagePercentText.text = context.getString(
                R.string.storage_usage_percent_format, usage.usedPercent
            )
            binding.usagePercentText.setTextColor(brandColor)
        } else {
            binding.usageContainer.isVisible = false
        }
    }

    override fun onCheckCanStartDrag(holder: ViewHolder, position: Int, x: Int, y: Int): Boolean =
        (holder.binding.root as ViewGroup).isTransformedTouchPointInViewCompat(
            x.toFloat(), y.toFloat(), holder.binding.dragHandleView, null
        )

    override fun onGetItemDraggableRange(holder: ViewHolder, position: Int): ItemDraggableRange? =
        null

    override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean = true

    override fun onItemDragStarted(position: Int) {
        notifyDataSetChanged()
    }

    override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {
        notifyDataSetChanged()
    }

    override fun onMoveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) {
            return
        }
        listener.moveStorage(fromPosition, toPosition)
    }

    class ViewHolder(val binding: StorageItemBinding) : AbstractDraggableItemViewHolder(
        binding.root
    )

    interface Listener {
        fun editStorage(storage: Storage)
        fun moveStorage(fromPosition: Int, toPosition: Int)
    }
}
