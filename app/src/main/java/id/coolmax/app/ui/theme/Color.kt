package id.coolmax.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Neumorphism palette: the surface, the highlight and the shadow are three steps of the same hue,
 * so raised/inset elements read as being moulded out of one soft slab.
 */

// Light theme
//
// Accent, muted, danger, warning and success are darkened just enough that each one still clears
// WCAG AA (4.5:1) when it is used as text on the light surface, including the slightly darker
// INSET fill (#E0E7EE), which is the worst case for contrast. The hues are unchanged; only the
// lightness moved, so the palette still reads as the same blue / slate / red family.
val NeuBaseLight = Color(0xFFE9EEF4)
val NeuHighlightLight = Color(0xFFFFFFFF)
val NeuShadowLight = Color(0xFFB9C5D4)
val NeuAccentLight = Color(0xFF355AD4)
val NeuOnBaseLight = Color(0xFF2A3442)
val NeuMutedLight = Color(0xFF596677)
val NeuDangerLight = Color(0xFFB02E29)
val NeuWarningLight = Color(0xFF945500)
val NeuSuccessLight = Color(0xFF22714F)

// Dark theme (shadows invert: the highlight is a lighter grey, the shadow is near-black)
val NeuBaseDark = Color(0xFF232830)
val NeuHighlightDark = Color(0xFF2F3641)
val NeuShadowDark = Color(0xFF14181E)
val NeuAccentDark = Color(0xFF8FA6FF)
val NeuOnBaseDark = Color(0xFFE8EDF5)
val NeuMutedDark = Color(0xFF9BA7B8)
val NeuDangerDark = Color(0xFFFF8A80)
val NeuWarningDark = Color(0xFFFFC46B)
val NeuSuccessDark = Color(0xFF7CD8AE)
