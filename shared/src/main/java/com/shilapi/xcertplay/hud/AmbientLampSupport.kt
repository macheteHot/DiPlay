package com.shilapi.xcertplay.hud

/**
 * Which lamp backend this head unit uses.
 *
 * The settings UI words itself around the backend: BYD's route needs existing ADB authorization and a
 * signed-in lamp interface, while the Geely / Lynk & Co head unit reaches the ECARX car API in-process
 * (see [EcarxAmbientLightPolicy]). Saying "ADB" on that unit would be wrong, so the copy is chosen here
 * rather than hard-coded in the dialog.
 */
object AmbientLampSupport {

    /** True when DiPlay drives the ECARX lamps: no ADB, no helper process. */
    fun usesEcarxCar(): Boolean = EcarxAmbientLightPolicy.supported()
}
