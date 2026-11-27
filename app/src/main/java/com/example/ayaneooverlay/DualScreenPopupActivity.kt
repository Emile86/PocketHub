package com.example.ayaneooverlay

/**
 * Shows only PocketHub's Dual screen controls window over the current app (used by a
 * remapped button). Reuses MainActivity's window code; finishes when the window closes.
 */
class DualScreenPopupActivity : MainActivity() {
    override val popupMode: Boolean = true
}
