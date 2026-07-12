package com.mangareader.utils

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Minimal "cast to secondary display" helper. On Android 11+ users can
 * present on an external display (Chromecast, Miracast dongle, USB-C hub
 * screen, etc.) using the platform's [Presentation] API — no Google Cast
 * SDK is required.
 *
 * Pages are pushed to the secondary display as ImageView content. This is
 * sufficient for the slideshow / reader use case.
 */
class ExternalDisplayCast(private val context: Context) {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var presentation: PagePresentation? = null

    fun availableDisplays(): Array<Display> = displayManager.displays

    fun hasExternalDisplay(): Boolean = displayManager.displays.any { it.displayId != 0 }

    /**
     * Open a Presentation on the first external display, or on the chosen
     * one if multiple are connected. Returns true on success.
     */
    fun start(preferredDisplay: Display? = null): Boolean {
        if (presentation?.isShowing == true) return true
        val display = preferredDisplay ?: displayManager.displays
            .firstOrNull { it.displayId != 0 } ?: return false
        val p = PagePresentation(context, display)
        p.show()
        presentation = p
        return true
    }

    fun showPage(bitmap: Bitmap?) {
        presentation?.showPage(bitmap)
    }

    fun stop() {
        presentation?.dismiss()
        presentation = null
    }

    private class PagePresentation(
        private val ctx: Context,
        display: Display
    ) : Presentation(ctx, display) {

        private val imageView: ImageView by lazy {
            ImageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF000000.toInt())
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = FrameLayout(ctx)
            root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            root.addView(imageView)
            setContentView(root)
        }

        fun showPage(bitmap: Bitmap?) {
            if (bitmap == null) return
            imageView.setImageBitmap(bitmap)
        }
    }
}
