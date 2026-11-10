package id.coolmax.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.coolmax.app.ui.theme.LocalNeuPalette

/**
 * Neumorphic design system.
 *
 * Every surface shares one base color; depth comes from two soft shadows (highlight top-left, shadow
 * bottom-right). Selected/pressed elements use the inset variant (gradient edge + slightly darker
 * fill), so the UI reads as one moulded slab instead of floating Material cards.
 *
 * Soft shadows rely on [Modifier.blur] (a RenderEffect), so real blur only renders on Android 12+.
 * On older versions the offset layers still convey depth, just without the soft falloff.
 */
enum class NeuVariant { RAISED, INSET, FLAT }

enum class NeuButtonStyle { PRIMARY, SOFT, GHOST }

@Composable
fun NeuSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    variant: NeuVariant = NeuVariant.RAISED,
    backgroundColor: Color = LocalNeuPalette.current.base,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    focused: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalNeuPalette.current
    val raised = variant == NeuVariant.RAISED
    val inset = variant == NeuVariant.INSET
    val offset = 5.dp
    val blurRadius = 11.dp

    Box(modifier = modifier) {
        if (raised) {
            // Highlight (top-left) then shadow (bottom-right); both behind the surface fill.
            SoftShadowLayer(offsetX = -offset, offsetY = -offset, blur = blurRadius, color = palette.highlight, shape = shape)
            SoftShadowLayer(offsetX = offset, offsetY = offset, blur = blurRadius, color = palette.shadow, shape = shape)
        }

        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (inset) lerp(backgroundColor, palette.shadow, 0.18f) else backgroundColor)
                .then(
                    if (inset) {
                        Modifier.border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(listOf(palette.shadow, palette.highlight)),
                            shape = shape
                        )
                    } else {
                        Modifier
                    }
                )
        )

        // Content is the sizing child; shadows and fill use matchParentSize above it.
        Box(Modifier.padding(contentPadding), content = content)

        // Every neumorphic control is clickable with `indication = null`, because a Material ripple
        // breaks the moulded-slab surface. That also removes the default focus ring, so a keyboard or
        // D-pad user would get no feedback at all. This border restores a visible focus state.
        if (focused) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(width = 2.dp, color = palette.accent, shape = shape)
            )
        }
    }
}

@Composable
private fun BoxScope.SoftShadowLayer(
    offsetX: Dp,
    offsetY: Dp,
    blur: Dp,
    color: Color,
    shape: Shape
) {
    Box(
        Modifier
            .matchParentSize()
            .offset(x = offsetX, y = offsetY)
            .blur(blur, BlurredEdgeTreatment.Unbounded)
            .background(color, shape)
    )
}

@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    variant: NeuVariant = NeuVariant.RAISED,
    backgroundColor: Color = LocalNeuPalette.current.base,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    NeuSurface(modifier = modifier.fillMaxWidth(), shape = shape, variant = variant, backgroundColor = backgroundColor) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun NeuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: NeuButtonStyle = NeuButtonStyle.PRIMARY,
    content: @Composable RowScope.() -> Unit
) {
    val palette = LocalNeuPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val background = when (style) {
        NeuButtonStyle.PRIMARY -> palette.accent
        NeuButtonStyle.SOFT, NeuButtonStyle.GHOST -> palette.base
    }
    val contentColor = when (style) {
        // onAccent, not plain white: the dark accent is light, so white would land at 2.3:1.
        NeuButtonStyle.PRIMARY -> palette.onAccent
        NeuButtonStyle.SOFT, NeuButtonStyle.GHOST -> palette.onBase
    }

    NeuSurface(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(16.dp),
        variant = if (pressed && enabled) NeuVariant.INSET else NeuVariant.RAISED,
        backgroundColor = background,
        focused = focused && enabled
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                modifier = Modifier
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

@Composable
fun NeuOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = NeuButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    style = NeuButtonStyle.SOFT,
    content = content
)

@Composable
fun NeuIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalNeuPalette.current.onBase
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    NeuSurface(
        modifier = modifier
            .size(44.dp)
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = CircleShape,
        variant = if (pressed) NeuVariant.INSET else NeuVariant.RAISED,
        focused = focused
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
        }
    }
}

@Composable
fun NeuSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalNeuPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val trackColor by animateColorAsState(
        if (checked) palette.accent else palette.base,
        label = "neuSwitchTrack"
    )
    val thumbPadding by animateDpAsState(if (checked) 27.dp else 4.dp, label = "neuSwitchThumb")

    // `toggleable` instead of `clickable`: it publishes the checked state to accessibility services,
    // so TalkBack announces "on"/"off" rather than an unlabelled clickable box.
    Box(
        modifier = modifier
            // The drawn track is 30dp tall. This keeps the tap target and focus box at 48dp without
            // making the switch itself look larger.
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled && onCheckedChange != null,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = { onCheckedChange?.invoke(it) }
            ),
        contentAlignment = Alignment.Center
    ) {
        NeuSurface(
            modifier = Modifier
                .size(width = 54.dp, height = 30.dp)
                .alpha(if (enabled) 1f else 0.45f),
            shape = RoundedCornerShape(50),
            variant = NeuVariant.INSET,
            backgroundColor = trackColor,
            focused = focused
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.CenterStart)
                    .padding(start = thumbPadding)
                    .size(23.dp)
                    .shadow(3.dp, CircleShape, spotColor = palette.shadow)
                    .background(if (checked) Color.White else palette.base, CircleShape)
            )
        }
    }
}

@Composable
fun NeuChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalNeuPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // Chips are only about 38dp tall. The outer box pads the tap target to 48dp while the drawn pill
    // keeps its own size, so selection rows do not turn chunky.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        NeuSurface(
            shape = RoundedCornerShape(50),
            variant = if (selected) NeuVariant.INSET else NeuVariant.RAISED,
            focused = focused && enabled
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (selected) palette.accent else palette.onBase
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    label()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    showTicks: Boolean = false
) {
    val palette = LocalNeuPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interactionSource,
        colors = SliderDefaults.colors(
            activeTrackColor = palette.accent,
            inactiveTrackColor = palette.shadow.copy(alpha = 0.35f),
            activeTickColor = if (showTicks) Color.White.copy(alpha = 0.45f) else Color.Transparent,
            inactiveTickColor = if (showTicks) palette.shadow.copy(alpha = 0.3f) else Color.Transparent,
            // Material's disabled defaults are dark greys that break the neumorphic palette.
            disabledActiveTrackColor = palette.accent.copy(alpha = 0.3f),
            disabledInactiveTrackColor = palette.shadow.copy(alpha = 0.22f),
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent
        ),
        // Material 3.3 draws a thin vertical bar thumb, which reads as a stray line in a neumorphic
        // surface, so the thumb is a round knob sitting on the track instead.
        thumb = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        if (enabled) palette.accent else lerp(palette.base, palette.shadow, 0.5f),
                        CircleShape
                    )
                    .border(width = 3.dp, color = palette.base, shape = CircleShape)
            )
        }
    )
}

@Composable
fun NeuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false
) {
    val palette = LocalNeuPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        readOnly = readOnly,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        isError = isError,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.onBase,
            unfocusedTextColor = palette.onBase,
            disabledTextColor = palette.muted,
            focusedContainerColor = palette.base,
            unfocusedContainerColor = palette.base,
            disabledContainerColor = palette.base,
            cursorColor = palette.accent,
            focusedBorderColor = palette.accent,
            unfocusedBorderColor = palette.highlight,
            disabledBorderColor = palette.shadow.copy(alpha = 0.4f),
            errorBorderColor = palette.danger,
            focusedLabelColor = palette.accent,
            unfocusedLabelColor = palette.muted,
            focusedLeadingIconColor = palette.accent,
            unfocusedLeadingIconColor = palette.muted,
            focusedTrailingIconColor = palette.accent,
            unfocusedTrailingIconColor = palette.muted
        )
    )
}

/** Small status pill used for Normal / Throttling / verified states. */
@Composable
fun NeuStatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    NeuSurface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        variant = NeuVariant.INSET
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
