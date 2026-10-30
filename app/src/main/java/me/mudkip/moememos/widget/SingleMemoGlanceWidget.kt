package me.mudkip.moememos.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.unit.ColorProvider
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.model.MemoEditGesture
import me.mudkip.moememos.data.service.MemoService
import me.mudkip.moememos.ext.settingsDataStore
import timber.log.Timber

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
        val refreshKey = prefs[SingleMemoWidgetKeys.refreshKey] ?: 0L
        var memo by remember { mutableStateOf<MemoEntity?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(memoId, refreshKey) {
            withContext(Dispatchers.IO) {
                try {
                    isLoading = true
                    memo = memoId?.let { memoService.getRepository().getMemo(it) }
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: "Unknown error"
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
                memoId == null -> CenteredMessage(context.getString(R.string.single_memo_widget_empty))
                isLoading -> CenteredMessage(context.getString(R.string.loading))
                error != null -> CenteredMessage(
                    error ?: context.getString(R.string.error_unknown),
                    color = GlanceTheme.colors.error
                )
                memo == null -> CenteredMessage(context.getString(R.string.memo_not_found))
                else -> Text(
                    text = memo?.content.orEmpty(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp
                    ),
                    maxLines = 20
                )
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
