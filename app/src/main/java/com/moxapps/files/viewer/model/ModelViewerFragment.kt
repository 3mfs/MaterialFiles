package com.moxapps.files.viewer.model

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moxapps.files.R
import com.moxapps.files.databinding.ModelViewerFragmentBinding
import com.moxapps.files.file.MimeType
import com.moxapps.files.file.asMimeTypeOrNull
import com.moxapps.files.file.fileProviderUri
import com.moxapps.files.filelist.name
import com.moxapps.files.provider.common.newInputStream
import com.moxapps.files.settings.Settings
import com.moxapps.files.util.ParcelableArgs
import com.moxapps.files.util.args
import com.moxapps.files.util.createSendStreamIntent
import com.moxapps.files.util.extraPath
import com.moxapps.files.util.finish
import com.moxapps.files.util.startActivitySafe
import com.moxapps.files.util.valueCompat
import com.moxapps.files.util.withChooser
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File
import java8.nio.file.Path

class ModelViewerFragment : Fragment() {
    private val args by args<Args>()

    private lateinit var binding: ModelViewerFragmentBinding
    private var isWireframe = false
    private var modelPath: Path? = null
    private var plates: List<ThreeMfPlateInfo> = emptyList()
    private var selectedPlateIndex = 0
    private var cached3mfFile: File? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        ModelViewerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.appBarLayout.applySystemWindowInsetsToPadding(left = true, top = true, right = true)
        binding.infoText.applySystemWindowInsetsToPadding(bottom = true)

        val intent = args.intent
        val path = intent.extraPath
        modelPath = path
        val uri = intent.data
        val displayName = path?.name ?: uri?.lastPathSegment ?: getString(R.string.model_viewer_title)
        activity.title = displayName

        if (path == null && uri == null) {
            finish()
            return
        }

        loadModel()
    }

    private fun loadModel() {
        val intent = args.intent
        val path = modelPath
        val uri = intent.data
        val displayName = path?.name ?: uri?.lastPathSegment ?: getString(R.string.model_viewer_title)

        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.errorText.isVisible = false
            binding.infoText.isVisible = false
            binding.previewImage.isVisible = false
            binding.glSurfaceView.clearMesh()
            val plateIdx = selectedPlateIndex
            val maxTriangles = Settings.MODEL_VIEWER_MAX_TRIANGLES.valueCompat.toIntOrNull()
                ?.coerceAtLeast(100_000) ?: MAX_VIEWER_TRIANGLES
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val is3mf = displayName.lowercase().endsWith(".3mf") ||
                        (uri != null && requireContext().contentResolver.getType(uri)
                            ?.contains("3mf", ignoreCase = true) == true)
                    if (is3mf) {
                        val file = cached3mfFile ?: File.createTempFile(
                            "viewer3mf_", ".3mf", requireContext().cacheDir
                        ).also { temp ->
                            val openStream = path?.newInputStream()
                                ?: requireContext().contentResolver.openInputStream(uri!!)
                                ?: throw IllegalStateException("Cannot open input stream")
                            openStream.use { input ->
                                temp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                            }
                            cached3mfFile = temp
                        }
                        val plateNum = plates.getOrNull(plateIdx)?.id?.toIntOrNull() ?: (plateIdx + 1)
                        val previewBytes = ModelMeshLoader.extract3mfPlatePreview(file, plateNum)
                        if (previewBytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    binding.previewImage.setImageBitmap(bmp)
                                    binding.previewImage.isVisible = true
                                }
                            }
                        }
                        ModelMeshLoader.load3mfFile(file, maxTriangles, plateIdx)
                    } else {
                        val openStream = path?.newInputStream()
                            ?: requireContext().contentResolver.openInputStream(uri!!)
                            ?: throw IllegalStateException("Cannot open input stream")
                        openStream.use { stream ->
                            ModelMeshLoader.loadStl(stream, maxTriangles)
                        }
                    }
                }
            }
            binding.progress.isVisible = false
            binding.previewImage.isVisible = false
            binding.previewImage.setImageDrawable(null)
            result.onSuccess { mesh ->
                plates = mesh.plates
                selectedPlateIndex = mesh.selectedPlateIndex
                requireActivity().invalidateOptionsMenu()
                binding.glSurfaceView.setMesh(mesh)
                val baseInfo = getString(
                    R.string.model_viewer_info_format,
                    mesh.triangleCount,
                    mesh.cadSizeX,
                    mesh.cadSizeY,
                    mesh.cadSizeZ
                )
                binding.infoText.text = if (mesh.plateHalfSize > 0f) {
                    val plateSize = mesh.plateHalfSize * 2f
                    "$baseInfo • " + getString(
                        R.string.model_viewer_plate_size_format, plateSize, plateSize
                    )
                } else {
                    baseInfo
                }
                binding.infoText.isVisible = true
            }.onFailure { e ->
                e.printStackTrace()
                binding.errorText.text = getString(R.string.model_viewer_load_error) +
                    "\n" + (e.localizedMessage ?: e.toString())
                binding.errorText.isVisible = true
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        cached3mfFile?.delete()
        cached3mfFile = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
    }

    override fun onPause() {
        binding.glSurfaceView.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.model_viewer, menu)
        menu.findItem(R.id.action_select_plate).apply {
            isVisible = plates.size > 1
            title = plates.getOrNull(selectedPlateIndex)?.label
                ?: getString(R.string.model_viewer_plate_default)
        }
        menu.findItem(R.id.action_wireframe).isChecked = isWireframe
        menu.findItem(R.id.action_share).isVisible = modelPath != null || args.intent.data != null
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_select_plate -> {
                showPlatePicker()
                true
            }
            R.id.action_reset_camera -> {
                binding.glSurfaceView.resetCamera()
                true
            }
            R.id.action_wireframe -> {
                isWireframe = !isWireframe
                item.isChecked = isWireframe
                binding.glSurfaceView.setWireframe(isWireframe)
                true
            }
            R.id.action_share -> {
                shareModel()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun showPlatePicker() {
        if (plates.size <= 1) return
        val anchor = binding.toolbar.findViewById<View>(R.id.action_select_plate) ?: binding.toolbar
        PopupMenu(requireContext(), anchor).apply {
            plates.forEachIndexed { index, plate ->
                menu.add(0, index, index, plate.label).apply {
                    isCheckable = true
                    isChecked = index == selectedPlateIndex
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { chosen ->
                if (chosen.itemId != selectedPlateIndex) {
                    selectedPlateIndex = chosen.itemId
                    requireActivity().invalidateOptionsMenu()
                    loadModel()
                }
                true
            }
            show()
        }
    }

    private fun shareModel() {
        val uri = modelPath?.fileProviderUri ?: args.intent.data ?: return
        val mimeType = args.intent.type?.asMimeTypeOrNull() ?: MimeType.GENERIC
        startActivitySafe(uri.createSendStreamIntent(mimeType).withChooser())
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    companion object {
        private const val MAX_VIEWER_TRIANGLES = 1_200_000
    }
}
