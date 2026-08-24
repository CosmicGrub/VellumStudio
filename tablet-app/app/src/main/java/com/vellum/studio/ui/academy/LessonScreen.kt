package com.vellum.studio.ui.academy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vellum.studio.academy.AcademyLibrary
import com.vellum.studio.academy.AcademyProgressRepository
import com.vellum.studio.academy.DemoPlayer
import com.vellum.studio.academy.Lesson
import com.vellum.studio.academy.LessonBlock
import com.vellum.studio.academy.LessonDemo
import com.vellum.studio.util.AssetBitmapCache
import com.vellum.studio.util.FoldPosture
import com.vellum.studio.util.primaryPaneWeightForHingeAngle
import com.vellum.studio.util.rememberFoldState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(
    courseId: String,
    lessonId: String,
    progressRepository: AcademyProgressRepository,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { progressRepository.load() }
    val completedKeys by progressRepository.completedLessonKeys
    val lesson = remember(courseId, lessonId) { AcademyLibrary.lesson(courseId, lessonId) }
    val scope = rememberCoroutineScope()
    val isComplete = lesson != null && "$courseId/$lessonId" in completedKeys
    // Foldable-aware layout signal only -- same FoldState detection EditorScreen's tabletop split
    // uses (see EditorScreen's own doc comment on this), reused rather than duplicated here.
    val foldState = rememberFoldState()

    fun toggleComplete() {
        scope.launch {
            if (isComplete) progressRepository.markIncomplete(courseId, lessonId)
            else progressRepository.markComplete(courseId, lessonId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lesson?.title ?: "Lesson", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (lesson != null) {
                        IconButton(onClick = ::toggleComplete) {
                            Icon(
                                if (isComplete) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                contentDescription = if (isComplete) "Marked complete" else "Mark complete",
                                tint = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (lesson == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        // The tabletop split only earns its keep when there's actually a demo to put in the top
        // pane -- a lesson with no LessonDemo (see AcademyLibrary) falls back to the normal
        // single-column layout even in HALF_OPENED_TABLETOP, same as every other posture.
        if (foldState.posture == FoldPosture.HALF_OPENED_TABLETOP && lesson.demo != null) {
            TabletopLessonLayout(
                lesson = lesson,
                demo = lesson.demo,
                hingeAngleDegrees = foldState.hingeAngleDegrees,
                hingeGapDp = with(LocalDensity.current) {
                    (foldState.hingeBounds?.height() ?: 0).coerceAtLeast(0).toDp()
                },
                isComplete = isComplete,
                onToggleComplete = ::toggleComplete,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(4.dp))
                lesson.blocks.forEach { block -> LessonBlockView(block) }

                lesson.demo?.let { demo ->
                    Spacer(Modifier.height(8.dp))
                    DemoSection(demo)
                }

                Spacer(Modifier.height(20.dp))
                MarkCompleteButton(isComplete, onClick = ::toggleComplete, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * HALF_OPENED_TABLETOP-only layout: the demo canvas fills the pane above the hinge, everything
 * else (lesson text, the same Step Through/Watch It Draw controls, and Mark Complete) scrolls in
 * the pane below it -- mirroring EditorScreen's canvas-above/controls-below tabletop split (see
 * [primaryPaneWeightForHingeAngle]) instead of this screen's normal single-column phone layout.
 */
@Composable
private fun TabletopLessonLayout(
    lesson: Lesson,
    demo: LessonDemo,
    hingeAngleDegrees: Float?,
    hingeGapDp: Dp,
    isComplete: Boolean,
    onToggleComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = rememberDemoController(demo)
    val scope = rememberCoroutineScope()
    val canvasWeight = primaryPaneWeightForHingeAngle(hingeAngleDegrees)

    Column(modifier) {
        Box(
            Modifier.weight(canvasWeight).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            DemoCanvasBox(demo, controller, Modifier.fillMaxHeight(), matchHeightConstraintsFirst = true)
        }
        Spacer(Modifier.height(hingeGapDp))
        Column(
            Modifier
                .weight(1f - canvasWeight)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            lesson.blocks.forEach { block -> LessonBlockView(block) }
            Spacer(Modifier.height(8.dp))
            DemoControlsCard(demo, controller, scope)
            Spacer(Modifier.height(20.dp))
            MarkCompleteButton(isComplete, onClick = onToggleComplete, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MarkCompleteButton(isComplete: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) {
        Icon(if (isComplete) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (isComplete) "Marked as complete" else "Mark lesson complete")
    }
}

@Composable
private fun LessonBlockView(block: LessonBlock) {
    when (block) {
        is LessonBlock.Heading -> Text(
            block.text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        )
        is LessonBlock.Paragraph -> Text(
            block.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        is LessonBlock.BulletList -> Column(Modifier.padding(vertical = 4.dp)) {
            block.items.forEach { item ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                    Text(item, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        is LessonBlock.Tip -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Row(Modifier.padding(12.dp)) {
                Icon(
                    Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(end = 10.dp).size(20.dp),
                )
                Text(block.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        is LessonBlock.Diagram -> DiagramView(block)
        is LessonBlock.MasterworkReference -> MasterworkReferenceView(block)
    }
}

@Composable
private fun MasterworkReferenceView(reference: LessonBlock.MasterworkReference) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val bitmapState = produceState<ImageBitmap?>(initialValue = null, reference) {
            value = withContext(Dispatchers.IO) {
                runCatching { AssetBitmapCache.get(context, reference.assetPath).asImageBitmap() }.getOrNull()
            }
        }
        val bitmap = bitmapState.value
        // Aspect ratio matches the real painting's proportions once loaded (these aren't square
        // like the procedural Diagram/Demo canvases) -- fall back to a plausible portrait ratio
        // for the one frame before it's decoded, to avoid a layout jump.
        val aspect = bitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: 0.8f
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            bitmap?.let { Image(bitmap = it, contentDescription = reference.caption, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            reference.caption,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            reference.attribution,
            style = MaterialTheme.typography.labelSmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagramView(diagram: LessonBlock.Diagram) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val bitmapState = produceState<ImageBitmap?>(initialValue = null, diagram) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    val size = 640
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(AndroidColor.WHITE)
                    diagram.draw(canvas, size)
                    bmp.asImageBitmap()
                }.getOrNull()
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            bitmapState.value?.let { Image(bitmap = it, contentDescription = diagram.caption, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            diagram.caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private enum class DemoUiMode { IDLE, STEPPING, WATCHING }

/**
 * Hoisted state for the "see it drawn" demo player -- one shared [DemoPlayer] canvas, driven
 * either stage-by-stage (paced "demonstration" mode, via the Next button) or straight through
 * (autoplaying "driven" mode, via Watch It Draw) — see the mode split documented on [DemoPlayer].
 *
 * Split out of what used to be a single `DemoSection` composable so the canvas ([DemoCanvasBox])
 * and its controls ([DemoControlsBody]) can be laid out separately -- above and below the hinge --
 * in [TabletopLessonLayout], while [DemoSection] itself still composes them together unchanged for
 * every other posture's single-column layout.
 */
private class DemoController {
    val player = DemoPlayer(800, 800)
    var mode by mutableStateOf(DemoUiMode.IDLE)
    var stageIndex by mutableStateOf(0)
    var job: Job? = null
}

@Composable
private fun rememberDemoController(demo: LessonDemo): DemoController {
    val controller = remember(demo) { DemoController() }
    DisposableEffect(demo) { onDispose { controller.job?.cancel() } }
    return controller
}

/** The demo canvas image plus its current-stage caption chip -- no controls, no title. Square
 * (the [DemoPlayer] canvas itself is 800x800); pass [matchHeightConstraintsFirst] = true when the
 * available *height* (not width) is the binding constraint, e.g. [TabletopLessonLayout]'s pane
 * above the hinge, so the square sizes off the pane's height instead of overflowing it. */
@Composable
private fun DemoCanvasBox(
    demo: LessonDemo,
    controller: DemoController,
    modifier: Modifier = Modifier,
    matchHeightConstraintsFirst: Boolean = false,
) {
    val revision = controller.player.revision
    val imageBitmap = remember(controller.player, revision) { controller.player.bitmap.asImageBitmap() }
    Box(
        modifier
            .aspectRatio(1f, matchHeightConstraintsFirst = matchHeightConstraintsFirst)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        Image(bitmap = imageBitmap, contentDescription = "Demo drawing", modifier = Modifier.fillMaxSize())
        val activeStage = controller.player.activeStageIndex
        if (activeStage in demo.stages.indices) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    demo.stages[activeStage].caption,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Step Through / Watch It Draw buttons plus the stage-progress row -- no canvas, no title. */
@Composable
private fun DemoControlsBody(demo: LessonDemo, controller: DemoController, scope: CoroutineScope) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                controller.job?.cancel()
                controller.player.reset()
                controller.mode = DemoUiMode.STEPPING
                controller.stageIndex = 0
                controller.job = scope.launch { controller.player.playStage(demo.stages[0], stageIndex = 0) }
            },
            enabled = demo.stages.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) { Text("Step Through") }
        Button(
            onClick = {
                controller.job?.cancel()
                controller.player.reset()
                controller.mode = DemoUiMode.WATCHING
                controller.job = scope.launch {
                    controller.player.playAll(demo)
                    controller.mode = DemoUiMode.IDLE
                }
            },
            enabled = demo.stages.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Watch It Draw")
        }
    }
    if (controller.mode == DemoUiMode.STEPPING) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Stage ${controller.stageIndex + 1} of ${demo.stages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val next = controller.stageIndex + 1
                if (next < demo.stages.size) {
                    controller.stageIndex = next
                    controller.job?.cancel()
                    controller.job = scope.launch { controller.player.playStage(demo.stages[next], stageIndex = next) }
                } else {
                    controller.mode = DemoUiMode.IDLE
                }
            }) { Text(if (controller.stageIndex + 1 < demo.stages.size) "Next Stage" else "Done") }
        }
    }
}

/** Title + subtitle + [DemoCanvasBox] + [DemoControlsBody] in one Card -- the normal single-column
 * "See It Drawn" panel, unchanged in appearance from before [DemoController] existed. */
@Composable
private fun DemoSection(demo: LessonDemo) {
    val controller = rememberDemoController(demo)
    val scope = rememberCoroutineScope()
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("See It Drawn", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Watch the technique happen start to finish, or step through it one stage at a time at your own pace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            DemoCanvasBox(demo, controller, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            DemoControlsBody(demo, controller, scope)
        }
    }
}

/** Title + subtitle + [DemoControlsBody] in a Card, deliberately WITHOUT [DemoCanvasBox] -- the
 * canvas already lives in [TabletopLessonLayout]'s separate pane above the hinge, sharing the same
 * [controller], so this is the bottom-pane half of that split rather than a full [DemoSection]. */
@Composable
private fun DemoControlsCard(demo: LessonDemo, controller: DemoController, scope: CoroutineScope) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("See It Drawn", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Watch the technique happen start to finish, or step through it one stage at a time at your own pace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            DemoControlsBody(demo, controller, scope)
        }
    }
}
