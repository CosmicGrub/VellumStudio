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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vellum.studio.academy.AcademyLibrary
import com.vellum.studio.academy.AcademyProgressRepository
import com.vellum.studio.academy.DemoPlayer
import com.vellum.studio.academy.Lesson
import com.vellum.studio.academy.LessonBlock
import com.vellum.studio.academy.LessonDemo
import com.vellum.studio.util.AssetBitmapCache
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lesson?.title ?: "Lesson", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (lesson != null) {
                        IconButton(onClick = {
                            scope.launch {
                                if (isComplete) progressRepository.markIncomplete(courseId, lessonId)
                                else progressRepository.markComplete(courseId, lessonId)
                            }
                        }) {
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            lesson.blocks.forEach { block -> LessonBlockView(block) }

            lesson.demo?.let { demo ->
                Spacer(Modifier.height(8.dp))
                DemoSection(demo)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (isComplete) progressRepository.markIncomplete(courseId, lessonId)
                        else progressRepository.markComplete(courseId, lessonId)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (isComplete) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isComplete) "Marked as complete" else "Mark lesson complete")
            }
            Spacer(Modifier.height(24.dp))
        }
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
        // Was `produceState`, converted to the equivalent remember+LaunchedEffect it desugars to
        // internally -- this codebase's Compose runtime version (BOM 2024.12.01, Kotlin 2.0.21)
        // has a confirmed-broken ProduceStateDoesNotAssignValue lint check that flags *every*
        // produceState call regardless of whether it assigns `value` (verified with a minimal
        // `produceState(0) { value = 1 }` repro), so this isn't a lint suppression, it's using the
        // identical underlying primitives directly.
        val bitmapState = remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(reference) {
            bitmapState.value = withContext(Dispatchers.IO) {
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
        // See the comment on MasterworkReferenceView's bitmapState above -- same
        // produceState-to-remember+LaunchedEffect conversion, same reason.
        val bitmapState = remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(diagram) {
            bitmapState.value = withContext(Dispatchers.Default) {
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
 * The "see it drawn" panel: one shared [DemoPlayer] canvas, driven either stage-by-stage (paced
 * "demonstration" mode, via the Next button) or straight through (autoplaying "driven" mode, via
 * Watch It Draw) — see the mode split documented on [DemoPlayer].
 */
@Composable
private fun DemoSection(demo: LessonDemo) {
    val player = remember(demo) { DemoPlayer(800, 800) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var mode by remember { mutableStateOf(DemoUiMode.IDLE) }
    var stageIndex by remember { mutableStateOf(0) }
    val revision = player.revision
    val imageBitmap = remember(player, revision) { player.bitmap.asImageBitmap() }

    DisposableEffect(demo) { onDispose { job?.cancel() } }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("See It Drawn", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Watch the technique happen start to finish, or step through it one stage at a time at your own pace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            ) {
                Image(bitmap = imageBitmap, contentDescription = "Demo drawing", modifier = Modifier.fillMaxSize())
                val activeStage = player.activeStageIndex
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
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        job?.cancel()
                        player.reset()
                        mode = DemoUiMode.STEPPING
                        stageIndex = 0
                        job = scope.launch { player.playStage(demo.stages[0], stageIndex = 0) }
                    },
                    enabled = demo.stages.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Step Through") }
                Button(
                    onClick = {
                        job?.cancel()
                        player.reset()
                        mode = DemoUiMode.WATCHING
                        job = scope.launch {
                            player.playAll(demo)
                            mode = DemoUiMode.IDLE
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
            if (mode == DemoUiMode.STEPPING) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Stage ${stageIndex + 1} of ${demo.stages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val next = stageIndex + 1
                        if (next < demo.stages.size) {
                            stageIndex = next
                            job?.cancel()
                            job = scope.launch { player.playStage(demo.stages[next], stageIndex = next) }
                        } else {
                            mode = DemoUiMode.IDLE
                        }
                    }) { Text(if (stageIndex + 1 < demo.stages.size) "Next Stage" else "Done") }
                }
            }
        }
    }
}
