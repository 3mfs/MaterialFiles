package com.moxapps.files.storage

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.NinePatchDrawable
import android.os.Bundle
import android.os.storage.StorageVolume
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils
import com.moxapps.files.R
import com.moxapps.files.databinding.StorageListFragmentBinding
import com.moxapps.files.file.asFileSize
import com.moxapps.files.settings.Settings
import com.moxapps.files.ui.ScrollingViewOnApplyWindowInsetsListener
import com.moxapps.files.util.createIntent
import com.moxapps.files.util.dpToDimensionPixelSize
import com.moxapps.files.util.fadeToVisibilityUnsafe
import com.moxapps.files.util.getColorByAttr
import com.moxapps.files.util.getDrawable
import com.moxapps.files.util.startActivitySafe
import com.moxapps.files.util.valueCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageListFragment : Fragment(), StorageListAdapter.Listener {
    private lateinit var binding: StorageListFragmentBinding

    private lateinit var adapter: StorageListAdapter
    private lateinit var dragDropManager: RecyclerViewDragDropManager
    private lateinit var wrappedAdapter: RecyclerView.Adapter<*>

    private var latestStorages: List<Storage> = emptyList()
    private var latestVolumes: List<StorageVolume> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        StorageListFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        val primaryColor = activity.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
        binding.overviewInclude.overviewIconContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(primaryColor, 38))
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(
            activity, RecyclerView.VERTICAL, false
        )
        adapter = StorageListAdapter(this)
        dragDropManager = RecyclerViewDragDropManager().apply {
            setDraggingItemShadowDrawable(
                getDrawable(
                    com.h6ah4i.android.materialshadowninepatch.R.drawable.ms9_composite_shadow_z2
                ) as NinePatchDrawable
            )
        }
        wrappedAdapter = dragDropManager.createWrappedAdapter(adapter)
        binding.recyclerView.adapter = wrappedAdapter
        binding.recyclerView.itemAnimator = DraggableItemAnimator()
        dragDropManager.attachRecyclerView(binding.recyclerView)
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView)
        )
        binding.fab.setOnClickListener { onAddStorage() }

        Settings.STORAGES.observe(viewLifecycleOwner) { onStorageListChanged(it) }
        StorageVolumeListLiveData.observe(viewLifecycleOwner) { onStorageVolumesChanged(it) }
    }

    override fun onPause() {
        super.onPause()

        dragDropManager.cancelDrag()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        dragDropManager.release()
        WrapperAdapterUtils.releaseAll(wrappedAdapter)
    }

    private fun onStorageListChanged(storages: List<Storage>) {
        latestStorages = storages
        binding.emptyView.fadeToVisibilityUnsafe(storages.isEmpty())
        adapter.replace(storages)
        refreshStorageUsage()
    }

    private fun onStorageVolumesChanged(volumes: List<StorageVolume>) {
        latestVolumes = volumes
        refreshStorageUsage()
    }

    private fun refreshStorageUsage() {
        val context = context ?: return
        val storages = latestStorages
        val volumes = latestVolumes
        viewLifecycleOwner.lifecycleScope.launch {
            val (overview, usageMap) = withContext(Dispatchers.IO) {
                val ov = StorageUsage.computeOverview(storages, volumes, context)
                val map = buildMap<Long, StorageUsageInfo> {
                    for (s in storages) {
                        StorageUsage.getStorageUsage(s, context)?.let { put(s.id, it) }
                    }
                }
                ov to map
            }
            adapter.setUsageMap(usageMap)
            bindOverview(overview)
        }
    }

    private fun bindOverview(overview: StorageOverview) {
        val context = context ?: return
        val card = binding.overviewInclude
        card.overviewCard.isVisible = overview.totalCapacityBytes > 0L ||
            overview.localDiskCount > 0 || overview.remoteStorageCount > 0

        card.overviewSubtitleText.text = getString(
            R.string.storage_overview_subtitle_format,
            overview.localDiskCount,
            overview.remoteStorageCount
        )
        card.overviewPercentText.text = "${overview.usedPercent}%"
        card.segmentedBar.setData(overview.segments, overview.totalCapacityBytes)

        val usedStr = overview.totalUsedBytes.asFileSize().formatHumanReadable(context)
        val totalStr = overview.totalCapacityBytes.asFileSize().formatHumanReadable(context)
        card.overviewUsedText.text = getString(R.string.storage_overview_used_format, usedStr)
        card.overviewTotalText.text = getString(R.string.storage_overview_total_format, totalStr)

        if (overview.cacheBytes > 0L) {
            card.cacheRow.isVisible = true
            card.cacheSizeText.text = overview.cacheBytes.asFileSize().formatHumanReadable(context)
        } else {
            card.cacheRow.isVisible = false
        }

        val legend = card.legendContainer
        legend.removeAllViews()
        card.legendScrollView.isVisible = overview.segments.isNotEmpty()
        val dotSize = context.dpToDimensionPixelSize(8)
        val itemGap = context.dpToDimensionPixelSize(14)
        val dotGap = context.dpToDimensionPixelSize(6)
        val textSecondary = context.getColorByAttr(android.R.attr.textColorSecondary)

        for ((index, seg) in overview.segments.withIndex()) {
            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (index > 0) {
                    setPadding(itemGap, 0, 0, 0)
                }
            }
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(seg.color)
                }
            }
            val labelView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dotGap }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(textSecondary)
                val segUsed = seg.usedBytes.asFileSize().formatHumanReadable(context)
                text = "${seg.label} ($segUsed)"
            }
            itemLayout.addView(dot)
            itemLayout.addView(labelView)
            legend.addView(itemLayout)
        }
    }

    private fun onAddStorage() {
        startActivitySafe(AddStorageDialogActivity::class.createIntent())
    }

    override fun editStorage(storage: Storage) {
        startActivitySafe(storage.createEditIntent())
    }

    override fun moveStorage(fromPosition: Int, toPosition: Int) {
        Storages.move(fromPosition, toPosition)
    }
}
