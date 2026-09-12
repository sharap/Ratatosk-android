package chat.ratatosk.android.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Общее состояние жеста «назад» для двух слоёв.
 *
 * Вынесено из компонента наружу нарочно. Нижний слой — экран, куда мы
 * возвращаемся, — **не пересоздаётся** на каждый жест: он лежит в дереве
 * всегда и просто читает отсюда смещение. Раньше он компоновался заново
 * в начале каждого жеста, и это было видно: список пересобирался, Coil
 * перезапрашивал аватарки, и они моргали.
 *
 * Ни одно поле не читается в теле composable — только в лямбдах отрисовки
 * и в корутинах, иначе кадр жеста перекомпоновывал бы оба экрана.
 */
@Stable
class SwipeBackState internal constructor() {
    internal val offset = mutableFloatStateOf(0f)
    internal val width = mutableFloatStateOf(0f)

    /** Идёт ли жест (или доводящая его анимация). */
    internal var active by mutableStateOf(false)

    internal fun reset() {
        offset.floatValue = 0f
        active = false
    }
}

@Composable
fun rememberSwipeBackState(): SwipeBackState = remember { SwipeBackState() }

/**
 * Нижний слой: экран, из-под которого уезжает верхний.
 *
 * Пока жеста нет, модификатор не делает ничего — слой рисуется как обычно.
 * Во время жеста он догоняет палец с отставанием ([PARALLAX]) и снимает
 * с себя затемнение. Скачка в начале не видно: при нулевом смещении
 * верхний слой закрывает его целиком.
 *
 * @param blockInput отдавать ли касания вниз. Когда сверху лежит другой
 *   экран — нет: иначе нажатие в пустом месте чата доставалось бы ещё
 *   и списку под ним.
 */
fun Modifier.swipeBackUnderlay(state: SwipeBackState, blockInput: Boolean): Modifier = this
    .graphicsLayer {
        translationX = if (state.active) -(size.width - state.offset.floatValue) * PARALLAX else 0f
    }
    .drawWithContent {
        drawContent()
        if (state.active) {
            val progress = (state.offset.floatValue / size.width).coerceIn(0f, 1f)
            drawRect(color = Color.Black, alpha = SCRIM_ALPHA * (1f - progress))
        }
    }
    .then(
        if (blockInput) {
            Modifier.pointerInput(Unit) {
                // Гасим на Initial, то есть до собственных детей слоя.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
        } else Modifier
    )

/**
 * Верхний слой: экран, который уходит вправо за пальцем.
 *
 * Кладётся в тот же [Box], что и слой с [swipeBackUnderlay], поверх него.
 *
 * # Откуда ловится жест
 *
 * * **полоса [edgeWidth] у левого края** — перехват на
 *   [PointerEventPass.Initial], раньше детей: здесь «назад» главнее всего;
 * * **остальной экран** — на [PointerEventPass.Main], после детей и только
 *   если никто из них касание не забрал. Так свайп работает откуда угодно,
 *   но не отнимает свайп-ответ у сообщений и прокрутку у списков.
 *
 * # Системный жест
 *
 * На телефоне с жестовой навигацией край принадлежит системе, и касание
 * до приложения не доходит. Поэтому есть второй вход — [PredictiveBackHandler]:
 * система отдаёт прогресс, рисуем по нему мы. Туда же приходит и обычное
 * «назад» кнопкой.
 */
@Composable
fun SwipeBackLayer(
    state: SwipeBackState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentKey: Any? = null,
    edgeWidth: Dp = 32.dp,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(contentKey) { state.reset() }
    LaunchedEffect(enabled) { if (!enabled) state.reset() }
    // Слой ушёл из дерева — снимаем смещение с нижнего, он остаётся жить.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { state.reset() }
    }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event: BackEventCompat ->
                if (!state.active) state.active = true
                state.offset.floatValue = event.progress * state.width.floatValue
            }
            settle(state, target = state.width.floatValue, spec = tween(160))
            // Смена содержимого и сброс смещения — одним снимком, иначе
            // новый экран мелькнул бы кадр сдвинутым на всю ширину.
            onBack()
            state.reset()
        } catch (cancelled: CancellationException) {
            settle(state, target = 0f, spec = spring(stiffness = 900f))
            state.active = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { state.width.floatValue = it.width.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val edgePx = edgeWidth.toPx()
                val slop = viewConfiguration.touchSlop
                val width = size.width.toFloat()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val fromEdge = down.position.x <= edgePx
                    val pass = if (fromEdge) PointerEventPass.Initial else PointerEventPass.Main

                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var travelX = 0f
                    var travelY = 0f
                    var captured = false

                    while (true) {
                        val event = awaitPointerEvent(pass)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!captured && change.isConsumed) {
                            // Жест забрал ребёнок — свайп-ответ, прокрутка.
                            return@awaitEachGesture
                        }

                        if (!change.pressed) {
                            if (captured) {
                                change.consume()
                                val velocity = tracker.calculateVelocity().x
                                val goBack = state.offset.floatValue > width * DISMISS_FRACTION ||
                                    velocity > FLING_VELOCITY
                                scope.launch {
                                    if (goBack) {
                                        settle(state, width, tween(durationMillis = 180))
                                        onBack()
                                        state.reset()
                                    } else {
                                        settle(state, 0f, spring(stiffness = 900f))
                                        state.active = false
                                    }
                                }
                            }
                            break
                        }

                        val delta = change.positionChange()
                        travelX += delta.x
                        travelY += delta.y
                        tracker.addPosition(change.uptimeMillis, change.position)

                        if (!captured) {
                            if (abs(travelY) > slop && abs(travelY) > abs(travelX)) return@awaitEachGesture
                            if (travelX < -slop) return@awaitEachGesture
                            if (travelX > slop) {
                                captured = true
                                state.active = true
                            }
                        }

                        if (captured) {
                            change.consume()
                            state.offset.floatValue =
                                (state.offset.floatValue + delta.x).coerceIn(0f, width)
                        }
                    }
                }
            }
            .graphicsLayer {
                val value = state.offset.floatValue
                translationX = value
                shadowElevation = if (value > 0f) SHADOW_DP.toPx() else 0f
            }
    ) {
        content()
    }
}

/** Доля ширины, после которой отпускание значит «назад», а не «вернуть». */
private const val DISMISS_FRACTION = 0.35f

/** Бросок пальцем: быстрый жест уводит назад, не дойдя до доли выше, px/с. */
private const val FLING_VELOCITY = 1000f

private const val PARALLAX = 0.25f
private const val SCRIM_ALPHA = 0.25f
private val SHADOW_DP = 16.dp

/** Доводит смещение до [target], не трогая композицию. */
private suspend fun settle(state: SwipeBackState, target: Float, spec: AnimationSpec<Float>) {
    animate(initialValue = state.offset.floatValue, targetValue = target, animationSpec = spec) { value, _ ->
        state.offset.floatValue = value
    }
}
