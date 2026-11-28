package com.moxapps.files.viewer.model

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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
import com.moxapps.files.util.ParcelableArgs
import com.moxapps.files.util.args
import com.moxapps.files.util.createSendStreamIntent
import com.moxapps.files.util.extraPath
import com.moxapps.files.util.finish
import com.moxapps.files.util.startActivitySafe
import com.moxapps.files.util.withChooser
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java8.nio.file.Path

class ModelViewerFragment : Fragment() {
    private val args by args<Args>()

    private lateinit var binding: ModelViewerFragmentBinding
    private var isWireframe = false
    private var modelPath: Path? = null

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

        viewLifecycleOwner.lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.errorText.isVisible = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val is3mf = displayName.lowercase().endsWith(".3mf") ||
                        (uri != null && requireContext().contentResolver.getType(uri)
                            ?.contains("3mf", ignoreCase = true) == true)
                    val openStream = {
                        path?.newInputStream()
                            ?: requireContext().contentResolver.openInputStream(uri!!)
                            ?: throw IllegalStateException("Cannot open input stream")
                    }
                    openStream().use { stream ->
                        if (is3mf) {
                            ModelMeshLoader.load3mf(stream, MAX_VIEWER_TRIANGLES)
                        } else {
                            ModelMeshLoader.loadStl(stream, MAX_VIEWER_TRIANGLES)
                        }
                    }
                }
            }
            binding.progress.isVisible = false
            result.onSuccess { mesh ->
                binding.glSurfaceView.setMesh(mesh)
                binding.infoText.text = getString(
                    R.string.model_viewer_info_format,
                    mesh.triangleCount,
                    mesh.cadSizeX,
                    mesh.cadSizeY,
                    mesh.cadSizeZ
                )
                binding.infoText.isVisible = true
            }.onFailure { e ->
                e.printStackTrace()
                binding.errorText.text = getString(R.string.model_viewer_load_error) +
                    "\n" + (e.localizedMessage ?: e.toString())
                binding.errorText.isVisible = true
            }
        }
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

    private fun shareModel() {
        val uri = modelPath?.fileProviderUri ?: args.intent.data ?: return
        val mimeType = args.intent.type?.asMimeTypeOrNull() ?: MimeType.GENERIC
        startActivitySafe(uri.createSendStreamIntent(mimeType).withChooser())
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    companion object {
        private const val MAX_VIEWER_TRIANGLES = 400_000
    }
}
