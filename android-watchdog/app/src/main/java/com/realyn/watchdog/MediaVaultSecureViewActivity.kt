package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityMediaVaultSecureViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class MediaVaultSecureViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaVaultSecureViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        binding = ActivityMediaVaultSecureViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.secureViewStatusLabel.text = getString(R.string.media_vault_secure_view_loading)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty().trim()
        if (itemId.isBlank()) {
            renderError(getString(R.string.media_vault_secure_view_load_failed))
            return
        }
        loadSecureImage(itemId)
    }

    override fun onDestroy() {
        binding.secureImageView.setImageDrawable(null)
        super.onDestroy()
    }

    private fun loadSecureImage(itemId: String) {
        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    MediaVaultFileStore.loadSecureImageForViewer(this@MediaVaultSecureViewActivity, itemId)
                }.getOrNull()
            }
            if (image == null) {
                renderError(getString(R.string.media_vault_secure_view_load_failed))
                return@launch
            }

            val bitmap = withContext(Dispatchers.Default) {
                decodeBoundedBitmap(image.bytes)
            }
            if (bitmap == null) {
                renderError(getString(R.string.media_vault_secure_view_load_failed))
                return@launch
            }

            binding.secureViewProgress.visibility = View.GONE
            binding.secureViewStatusLabel.visibility = View.GONE
            binding.secureImageView.visibility = View.VISIBLE
            binding.secureImageView.setImageBitmap(bitmap)
        }
    }

    private fun renderError(message: String) {
        binding.secureViewProgress.visibility = View.GONE
        binding.secureImageView.visibility = View.GONE
        binding.secureViewStatusLabel.visibility = View.VISIBLE
        binding.secureViewStatusLabel.text = message
    }

    private fun decodeBoundedBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        var sampleSize = 1
        val maxDimension = max(bounds.outWidth, bounds.outHeight)
        while (maxDimension / sampleSize > MAX_VIEW_DIMENSION_PX) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        }.getOrNull()
    }

    companion object {
        private const val EXTRA_ITEM_ID = "extra_media_vault_item_id"
        private const val MAX_VIEW_DIMENSION_PX = 4096

        fun createIntent(context: Context, itemId: String): Intent {
            return Intent(context, MediaVaultSecureViewActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
            }
        }
    }
}
