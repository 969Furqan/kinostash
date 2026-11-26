package app.kinostash.android.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * Renderers with "legacy" subtitle decoding enabled. Media3 parses most subtitles while extracting,
 * but image-based subtitles embedded in MKV files (PGS from Blu-ray remuxes, VobSub) reach the
 * text renderer unparsed; without legacy decoding the player fails with ERROR_CODE_FAILED_RUNTIME_CHECK.
 */
@UnstableApi
class KinostashRenderers(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) })
    }
}
