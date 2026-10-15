package com.shilapi.xcertplay.media

/**
 * The animation the lamps run, on top of the colour the settings choose.
 *
 * [MUSIC] is the original behaviour: the brightness follows the music envelope and the colour follows
 * the chosen colour mode. [BREATHING] and [METEOR] animate the chosen colours at their own pace, which
 * only the Geely / Lynk & Co head unit can do: its lamp interface takes a continuous intensity (0..255)
 * and accepts per-zone writes, where BYD's route offers six levels and one front/rear split. The
 * settings UI therefore only offers these on the head unit that supports them.
 *
 * Every effect still uses the chosen colours, so the palette and the album-artwork source keep working.
 */
enum class AmbientEffect { MUSIC, BREATHING, METEOR }
