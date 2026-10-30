package me.mudkip.moememos.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.mudkip.moememos.MainActivity
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.model.MemoEditGesture
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.service.MemoService
import me.mudkip.moememos.ext.settingsDataStore
import me.mudkip.moememos.util.MemoLineKind
import me.mudkip.moememos.util.lineAt
import me.mudkip.moememos.util.parseMemoChecklist
import me.mudkip.moememos.util.toggleCheckboxIn
import me.mudkip.moememos.util.visibleLines
import timber.log.Timber
import java.time.Instant

class SingleMemoGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val memoService = widgetEntryPoint.memoService()
        val settings = context.settingsDataStore.data.first()
        val openInEditor = settings.usersList
            .firstOrNull { it.accountKey == settings.currentUser }
            ?.settings?.editGesture == MemoEditGesture.SINGLE

        provideContent(createContent(context, memoService, openInEditor))
    }

    // Keep composable lambda captures outside the coroutine state machine so Compose
    // can collect stack trace mappings for the widget content.
    private fun createContent(
        context: Context,
        memoService: MemoService,
        openInEditor: Boolean
    ): @Composable () -> Unit = {
        val prefs = currentState<Preferences>()
        GlanceTheme {
            WidgetContent(context, memoService, prefs, openInEditor)
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        memoService: MemoService,
        prefs: Preferences,
        openInEditor: Boolean
    ) {
        val memoId = prefs[SingleMemoWidgetKeys.memoId]
        val accountKey = prefs[SingleMemoWidgetKeys.accountKey]
        val appWidgetId = prefs[SingleMemoWidgetKeys.appWidgetId]
        val refreshKey = prefs[SingleMemoWidgetKeys.refreshKey] ?: 0L
        var memo by remember { mutableStateOf<MemoEntity?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var missing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(memoId, accountKey, refreshKey) {
            withContext(Dispatchers.IO) {
                try {
                    isLoading = true
                    // A memo bound to another account is unreachable from here, and reading it
                    // through the current account's repository would return a stale or foreign row.
                    val currentAccountKey = context.settingsDataStore.data.first().currentUser
                    val loaded = if (memoId == null || accountKey != currentAccountKey) {
                        null
                    } else {
                        memoService.getRepository().getMemo(memoId)
                    }
                    memo = loaded
                    missing = memoId != null && (loaded == null || loaded.isDeleted || loaded.archived)
                    error = null
                } catch (e: Exception) {
                    error = context.getString(R.string.error_unknown)
                    Timber.tag("SingleMemoWidget").e(e, "Exception in single memo widget")
                } finally {
                    isLoading = false
                }
            }
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(12.dp)
        ) {
            when {
                isLoading -> CenteredMessage(context.getString(R.string.loading))
                error != null -> CenteredMessage(
                    error ?: context.getString(R.string.error_unknown),
                    color = GlanceTheme.colors.error
                )
                memoId == null -> ConfigureMessage(
                    context,
                    appWidgetId,
                    context.getString(R.string.single_memo_widget_empty)
                )
                missing || memo == null -> ConfigureMessage(
                    context,
                    appWidgetId,
                    context.getString(R.string.single_memo_widget_missing)
                )
                else -> {
                    val loadedMemo = memo ?: return@Column
                    MemoContent(context, loadedMemo, accountKey, openInEditor)
                }
            }
        }
    }

    /** Center message that opens this widget's configuration when the bound memo can no longer be shown. */
    @Composable
    private fun ConfigureMessage(context: Context, appWidgetId: Int?, message: String) {
        Box(
            modifier = if (appWidgetId == null) {
                GlanceModifier.fillMaxSize()
            } else {
                GlanceModifier
                    .fillMaxSize()
                    .clickable(actionStartActivity(createConfigureIntent(context, appWidgetId)))
            },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = TextStyle(color = GlanceTheme.colors.onBackground)
            )
        }
    }

    @OptIn(ExperimentalGlanceApi::class)
    @Composable
    private fun MemoContent(
        context: Context,
        memo: MemoEntity,
        accountKey: String?,
        openInEditor: Boolean
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity(createMemoIntent(context, memo.identifier, openInEditor)))
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        memo.date.toEpochMilli(),
                        Instant.now().toEpochMilli(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
                if (memo.visibility != MemoVisibility.PUBLIC) {
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Image(
                        provider = ImageProvider(android.R.drawable.ic_lock_lock),
                        contentDescription = context.getString(R.string.memo_visibility_private),
                        modifier = GlanceModifier.size(14.dp)
                    )
                }
                if (memo.pinned) {
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Image(
                        provider = ImageProvider(R.drawable.ic_pin),
                        contentDescription = context.getString(R.string.pin),
                        modifier = GlanceModifier.size(14.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            val size = LocalSize.current
            val lineBudget = ((size.height.value - 24f - 20f - 8f) / 20f).toInt().coerceAtLeast(1)
            val parsedLines = parseMemoChecklist(memo.content)
            if (parsedLines.isEmpty()) {
                Text(
                    text = context.getString(R.string.single_memo_widget_empty_memo),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp)
                )
            } else {
                // The "…" indicator is a line of its own, so it has to be taken out of the budget
                // first: a line added after the budget is already full gets clipped by the widget.
                val showEllipsis = parsedLines.size > lineBudget && lineBudget > 1
                val contentBudget = if (showEllipsis) lineBudget - 1 else lineBudget
                val visible = visibleLines(parsedLines, contentBudget)
                visible.lines.forEach { line ->
                    when (line.kind) {
                        MemoLineKind.TEXT -> Text(
                            // Leading whitespace is rendered as padding instead of as text, so an
                            // indented line is not indented twice.
                            text = line.text.trimStart(),
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                            maxLines = 1,
                            modifier = GlanceModifier.padding(start = (8 * line.indentLevel).dp)
                        )
                        MemoLineKind.CHECKBOX -> CheckBox(
                            checked = line.checked,
                            onCheckedChange = actionRunCallback<ToggleCheckboxAction>(
                                actionParametersOf(
                                    ToggleCheckboxKeys.memoId to memo.identifier,
                                    ToggleCheckboxKeys.accountKey to accountKey.orEmpty(),
                                    ToggleCheckboxKeys.startOffset to line.startOffset,
                                    ToggleCheckboxKeys.endOffset to line.endOffset,
                                    ToggleCheckboxKeys.targetChecked to !line.checked,
                                    ToggleCheckboxKeys.expectedLine to
                                        memo.content.substring(line.startOffset, line.endOffset)
                                )
                            ),
                            text = line.text,
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                            maxLines = 1,
                            modifier = GlanceModifier.padding(start = (8 * line.indentLevel).dp)
                        )
                    }
                }
                if (showEllipsis) {
                    Text(
                        text = "…",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp)
                    )
                }
            }
        }
    }

    @Composable
    private fun CenteredMessage(
        message: String,
        color: ColorProvider = GlanceTheme.colors.onBackground
    ) {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = message, style = TextStyle(color = color))
        }
    }
}

class ToggleCheckboxAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val memoId = parameters[ToggleCheckboxKeys.memoId]
        val accountKey = parameters[ToggleCheckboxKeys.accountKey]
        val startOffset = parameters[ToggleCheckboxKeys.startOffset]
        val endOffset = parameters[ToggleCheckboxKeys.endOffset]
        val targetChecked = parameters[ToggleCheckboxKeys.targetChecked]
        val expectedLine = parameters[ToggleCheckboxKeys.expectedLine]

        if (memoId == null || accountKey == null || startOffset == null ||
            endOffset == null || targetChecked == null || expectedLine == null
        ) {
            refresh(context)
            return
        }

        try {
            val memoService = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java
            ).memoService()
            val currentAccountKey = context.settingsDataStore.data.first().currentUser
            if (accountKey != currentAccountKey) {
                refresh(context)
                return
            }

            val memo = withContext(Dispatchers.IO) { memoService.getRepository().getMemo(memoId) }
            if (memo == null || memo.isDeleted || memo.archived) {
                // Writing here would resurrect a deleted memo: updateMemo clears isDeleted.
                refresh(context)
                return
            }

            if (lineAt(memo.content, startOffset, endOffset) != expectedLine) {
                // The memo changed elsewhere and the offsets no longer point at the tapped line.
                refresh(context)
                return
            }

            val newContent = toggleCheckboxIn(memo.content, startOffset, endOffset, targetChecked)
            if (newContent != null) {
                withContext(Dispatchers.IO) {
                    memoService.getRepository().updateMemo(memoId, content = newContent)
                }
            }
        } catch (e: Exception) {
            Timber.tag("SingleMemoWidget").e(e, "Exception toggling checkbox in single memo widget")
        }

        refresh(context)
    }

    /**
     * Refreshes every widget rather than only this one: the write above bypasses the app's view
     * models, so another single memo widget bound to the same memo (or a list widget showing it)
     * would otherwise keep the old checklist until the next periodic refresh. Failures are logged
     * instead of escaping [onAction], where they would crash the widget's broadcast.
     */
    private suspend fun refresh(context: Context) {
        try {
            WidgetUpdateScheduler.updateAllWidgets(context)
        } catch (e: Exception) {
            Timber.tag("SingleMemoWidget").e(e, "Exception refreshing widgets after checkbox toggle")
        }
    }
}

object ToggleCheckboxKeys {
    val memoId: ActionParameters.Key<String> = ActionParameters.Key("single_memo_toggle_memo_id")
    val accountKey: ActionParameters.Key<String> = ActionParameters.Key("single_memo_toggle_account_key")
    val startOffset: ActionParameters.Key<Int> = ActionParameters.Key("single_memo_toggle_start")
    val endOffset: ActionParameters.Key<Int> = ActionParameters.Key("single_memo_toggle_end")
    val targetChecked: ActionParameters.Key<Boolean> = ActionParameters.Key("single_memo_toggle_checked")
    val expectedLine: ActionParameters.Key<String> = ActionParameters.Key("single_memo_toggle_line")
}

private fun createMemoIntent(context: Context, memoId: String, openInEditor: Boolean): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = if (openInEditor) MainActivity.ACTION_EDIT_MEMO else MainActivity.ACTION_VIEW_MEMO
        putExtra(MainActivity.EXTRA_MEMO_ID, memoId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

private fun createConfigureIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, SingleMemoWidgetConfigurationActivity::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
