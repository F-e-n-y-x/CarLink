package com.carlink.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.diagnostics.AppInfo
import com.carlink.app.ui.components.CarLinkMark
import com.carlink.app.ui.components.CarSheet
import com.carlink.app.ui.components.CreditItem
import com.carlink.app.ui.components.QrPanel
import com.carlink.app.ui.components.ltr
import com.carlink.app.ui.premium.PremiumScreen
import com.carlink.app.ui.settings.GlassGroup
import com.carlink.app.ui.settings.GroupDivider
import com.carlink.app.ui.settings.GroupHeader
import com.carlink.app.ui.settings.LinkRow
import com.carlink.app.ui.settings.RowGlyph
import com.carlink.app.ui.settings.SettingsRow
import com.carlink.app.ui.settings.Tag
import com.carlink.app.ui.settings.isCar
import com.carlink.app.ui.settings.rowPadding
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.settings.SwitchRow
import com.carlink.app.update.UpdateState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which parked-reading sheet About shows. */
internal enum class AboutSheet { NONE, LICENSES, CREDITS }

/** About › Updates: the updater's state as the row shows it (previews and the gallery supply their own). */
internal data class AboutUpdatesUi(
    /** The signed release build; other builds never check (update/Updates.kt). */
    val supported: Boolean,
    val state: UpdateState,
    val autoCheck: Boolean,
    /** Wall-clock time of the last check, 0 for never. */
    val lastCheck: Long,
    /** "Now" for the "last checked" text; fixed in the gallery. */
    val now: Long = System.currentTimeMillis(),
)

internal interface AboutUpdateActions {
    fun checkNow() {}
    fun setAutoCheck(enabled: Boolean) {}
}

internal object NoUpdateActions : AboutUpdateActions

/** Facts About shows; gathered once so previews can supply their own. */
internal data class AboutFacts(
    val versionName: String,
    val versionCode: Int,
    val carPlayEngine: String,
    val androidAutoEngine: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val screen: String,
)

/**
 * About: identity, versions, this device, licenses and credits, and the source as QR codes
 * (links are never typed in a car), on the same atmosphere and glass as Home and Settings.
 * Wide windows: identity and source on the start side, the information cards on the end side.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val facts = remember {
        val (w, h) = AppInfo.screenPixels(context)
        AboutFacts(
            versionName = AppInfo.versionName,
            versionCode = AppInfo.versionCode,
            carPlayEngine = AppInfo.carPlayEngineVersion,
            androidAutoEngine = AppInfo.androidAutoEngineVersion,
            manufacturer = AppInfo.manufacturer,
            model = AppInfo.model,
            androidRelease = AppInfo.androidRelease,
            screen = "$w×$h",
        )
    }
    val updater = remember { com.carlink.app.update.Updates.get(context) }
    val state by updater.state.collectAsStateWithLifecycle()
    val autoCheck by updater.autoCheck.collectAsStateWithLifecycle()
    val lastCheck by updater.lastCheck.collectAsStateWithLifecycle()
    val actions = remember(updater) {
        object : AboutUpdateActions {
            override fun checkNow() = updater.checkNow()
            override fun setAutoCheck(enabled: Boolean) = updater.setAutoCheck(enabled)
        }
    }
    // Leaving About clears its "You're up to date" / "Couldn't reach GitHub" answer.
    DisposableEffect(updater) {
        onDispose { if (updater.state.value is UpdateState.UpToDate || updater.state.value is UpdateState.CheckFailed) updater.dismiss() }
    }
    AboutContent(facts, onBack,
        updates = AboutUpdatesUi(updater.supported, state, autoCheck, lastCheck, System.currentTimeMillis()),
        updateActions = actions)
}

@Composable
internal fun AboutContent(
    facts: AboutFacts,
    onBack: () -> Unit,
    initialSheet: AboutSheet = AboutSheet.NONE,
    updates: AboutUpdatesUi = previewUpdates,
    updateActions: AboutUpdateActions = NoUpdateActions,
) {
    val dimens = CarLinkTokens.dimens
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    var licenseText by rememberSaveable { mutableStateOf<String?>(null) }
    val openLicenses = { sheet = AboutSheet.LICENSES }
    val openCredits = { sheet = AboutSheet.CREDITS }

    PremiumScreen(stringResource(R.string.carlink_about_title), onBack = onBack) { margin ->
        val gap = if (isCar) 24.dp else 16.dp
        val twoPane = maxWidth >= 840.dp && maxHeight >= 420.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize().padding(horizontal = margin)) {
                Column(
                    Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = dimens.gutter),
                    verticalArrangement = Arrangement.spacedBy(gap * 1.5f),
                ) {
                    Identity(facts, stacked = true)
                    SourceCard()
                }
                Spacer(Modifier.width(dimens.gutter * 1.5f))
                Column(
                    Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = dimens.gutter),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    UpdatesCard(facts, updates, updateActions)
                    InfoCards(facts, openLicenses, openCredits)
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = margin).padding(top = 8.dp, bottom = dimens.gutter * 2),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Identity(facts, stacked = false)
                    Spacer(Modifier.height(4.dp))
                    UpdatesCard(facts, updates, updateActions)
                    InfoCards(facts, openLicenses, openCredits, withLegal = false)
                    SourceCard()
                    LegalCard()
                }
            }
        }
    }

    when (sheet) {
        AboutSheet.NONE -> Unit
        AboutSheet.CREDITS -> CarSheet(stringResource(R.string.carlink_about_credits), onDismiss = { sheet = AboutSheet.NONE }) {
            CreditsContent()
        }
        AboutSheet.LICENSES -> {
            val close = { sheet = AboutSheet.NONE; licenseText = null }
            val path = licenseText
            if (path == null) {
                CarSheet(stringResource(R.string.carlink_about_licenses), onDismiss = close) {
                    LicensesContent(onRead = { licenseText = it })
                }
            } else {
                CarSheet(licenseTitle(path), onDismiss = close, onBack = { licenseText = null }, showBackArrow = true,
                    scrollable = false) {
                    LicenseText(path)
                }
            }
        }
    }
}

// ---- Identity and source ------------------------------------------------------------------

/** The mark, the name, what it does, and the version as a tag. */
@Composable
private fun Identity(facts: AboutFacts, stacked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val version = ltr(stringResource(R.string.carlink_about_version_value, facts.versionName, facts.versionCode))
    val versionTag = @Composable {
        Tag(stringResource(R.string.carlink_about_version_tag, version), scheme.onSurfaceVariant, R.drawable.carlink_ic_info)
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics(mergeDescendants = true) {}) {
            CarLinkMark(Modifier.size(if (isCar) 120.dp else 80.dp))
            Spacer(Modifier.height(if (isCar) 20.dp else 12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, color = scheme.onSurface,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.carlink_about_tagline), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(if (isCar) 24.dp else 16.dp))
            versionTag()
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically) {
            CarLinkMark(Modifier.size(if (isCar) 96.dp else 64.dp))
            Spacer(Modifier.width(if (isCar) 28.dp else 18.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = scheme.onSurface,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.carlink_about_tagline), style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                versionTag()
            }
        }
    }
}

@Composable
private fun SourceCard() {
    val qrSize = if (CarLinkTokens.profile == UiProfile.CAR) 200.dp else 148.dp
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_about_source), icon = R.drawable.carlink_ic_qr_code_2) }) {
        Column(Modifier.fillMaxWidth().padding(start = rowPadding, end = rowPadding, top = 6.dp, bottom = rowPadding)) {
            Text(stringResource(R.string.carlink_about_source_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(if (isCar) 24.dp else 16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // Text under each code can be wider than the code at large font scales; allow for it.
                val sideBySide = maxWidth >= qrSize * 2 + 48.dp
                val panels = @Composable {
                    QrPanel(stringResource(R.string.carlink_about_qr_carplay), AppInfo.CARPLAY_ENGINE_SOURCE, qrSize)
                    QrPanel(stringResource(R.string.carlink_about_qr_aa), AppInfo.ANDROID_AUTO_ENGINE_SOURCE, qrSize)
                }
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) { panels() }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) { panels() }
                }
            }
        }
    }
}

// ---- Updates ------------------------------------------------------------------------------

/** "Check for updates" with the answer under it, and the automatic-check switch. */
@Composable
private fun UpdatesCard(facts: AboutFacts, updates: AboutUpdatesUi, actions: AboutUpdateActions) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val state = updates.state
    val version = ltr(facts.versionName)
    val lastChecked = when {
        updates.lastCheck <= 0L -> stringResource(R.string.carlink_about_updates_never, version)
        else -> stringResource(R.string.carlink_about_updates_last_checked, version, relativeTime(updates.lastCheck, updates.now))
    }
    val available = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Verifying -> state.release
        is UpdateState.NeedsPermission -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    }
    val description = when {
        !updates.supported -> stringResource(R.string.carlink_about_updates_unsupported)
        state is UpdateState.Checking -> stringResource(R.string.carlink_about_updates_checking)
        state is UpdateState.CheckFailed -> stringResource(
            if (state.rateLimited) R.string.carlink_about_updates_rate_limited else R.string.carlink_about_updates_failed)
        else -> lastChecked
    }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_about_updates), icon = R.drawable.carlink_ic_system_update) }) {
        SettingsRow(
            stringResource(R.string.carlink_about_check_updates),
            description = description,
            icon = R.drawable.carlink_ic_refresh,
            enabled = updates.supported && state !is UpdateState.Checking,
            onClick = actions::checkNow,
            role = androidx.compose.ui.semantics.Role.Button,
            tags = when {
                !updates.supported -> null
                state is UpdateState.UpToDate -> {
                    { Tag(stringResource(R.string.carlink_about_updates_up_to_date), colors.success, R.drawable.carlink_ic_check_circle) }
                }
                available != null -> {
                    { Tag(stringResource(R.string.carlink_about_updates_available, ltr(available.version.toString())), scheme.primary,
                        R.drawable.carlink_ic_download) }
                }
                else -> null
            },
            trailing = if (state is UpdateState.Checking) {
                { CircularProgressIndicator(Modifier.size(CarLinkTokens.dimens.iconTertiary + 4.dp), color = scheme.primary, strokeWidth = 3.dp) }
            } else null,
        )
        GroupDivider()
        SwitchRow(stringResource(R.string.carlink_about_auto_updates), updates.autoCheck && updates.supported, actions::setAutoCheck,
            description = stringResource(R.string.carlink_about_auto_updates_body), icon = R.drawable.carlink_ic_schedule,
            enabled = updates.supported)
    }
}

/** "just now", "5 minutes ago", "2 hours ago", "3 days ago" (Android's wording). */
@Composable
private fun relativeTime(time: Long, now: Long): String =
    if (now - time in 0 until 60_000L) stringResource(R.string.carlink_about_updates_just_now)
    else android.text.format.DateUtils.getRelativeTimeSpanString(time, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

// ---- Information --------------------------------------------------------------------------

@Composable
private fun InfoCards(facts: AboutFacts, onLicenses: () -> Unit, onCredits: () -> Unit, withLegal: Boolean = true) {
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_about_engines)) }) {
        SettingsRow(stringResource(R.string.carlink_about_carplay_engine), icon = R.drawable.carlink_ic_phone_iphone,
            description = stringResource(R.string.carlink_about_carplay_engine_value, ltr(facts.carPlayEngine)))
        GroupDivider()
        SettingsRow(stringResource(R.string.carlink_about_aa_engine), icon = R.drawable.carlink_ic_android,
            description = stringResource(R.string.carlink_about_aa_engine_value, ltr(facts.androidAutoEngine)))
    }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_about_device)) }) {
        SettingsRow(stringResource(R.string.carlink_about_device_name, ltr(facts.manufacturer), ltr(facts.model)),
            icon = R.drawable.carlink_ic_devices,
            description = stringResource(R.string.carlink_about_device_system, ltr(facts.androidRelease), ltr(facts.screen)))
    }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_about_open_source)) }) {
        LinkRow(stringResource(R.string.carlink_about_licenses), onLicenses, stringResource(R.string.carlink_about_licenses_body),
            R.drawable.carlink_ic_gavel)
        GroupDivider()
        LinkRow(stringResource(R.string.carlink_about_credits), onCredits, stringResource(R.string.carlink_about_credits_body),
            R.drawable.carlink_ic_groups)
    }
    if (withLegal) LegalCard()
}

@Composable
private fun LegalCard() {
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_about_legal)) }) {
        Column(Modifier.fillMaxWidth().padding(start = rowPadding, end = rowPadding, top = 8.dp, bottom = rowPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.carlink_about_trademarks, R.string.carlink_about_not_certified, R.string.carlink_about_byd).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Sheets -------------------------------------------------------------------------------

@Composable
private fun ColumnScope.LicensesContent(onRead: (String) -> Unit) {
    Text(stringResource(R.string.carlink_about_licenses_intro), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.carlink_about_licenses_source), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(CarLinkTokens.dimens.gutter))
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_about_full_texts)) }) {
        LinkRow(stringResource(R.string.carlink_about_read_agpl), { onRead(AppInfo.AGPL_ASSET) }, icon = R.drawable.carlink_ic_description)
        GroupDivider()
        LinkRow(stringResource(R.string.carlink_about_read_gpl), { onRead(AppInfo.GPL_ASSET) }, icon = R.drawable.carlink_ic_description)
        GroupDivider()
        LinkRow(stringResource(R.string.carlink_about_read_bydmate), { onRead(AppInfo.BYDMATE_ASSET) }, icon = R.drawable.carlink_ic_description)
    }
    Spacer(Modifier.height(CarLinkTokens.dimens.gutter))
    GroupHeader(stringResource(R.string.carlink_about_components))
    Spacer(Modifier.height(4.dp))
    CreditItem(stringResource(R.string.carlink_about_lic_diplay_name), stringResource(R.string.carlink_about_lic_diplay))
    CreditItem(stringResource(R.string.carlink_about_lic_diauto_name), stringResource(R.string.carlink_about_lic_diauto))
    CreditItem(stringResource(R.string.carlink_about_lic_bydmate_name), stringResource(R.string.carlink_about_lic_bydmate))
    CreditItem(stringResource(R.string.carlink_about_lic_ffmpeg_name), stringResource(R.string.carlink_about_lic_ffmpeg))
    CreditItem(stringResource(R.string.carlink_about_lic_libusb_name), stringResource(R.string.carlink_about_lic_libusb))
    CreditItem(stringResource(R.string.carlink_about_lic_speex_name), stringResource(R.string.carlink_about_lic_speex))
    CreditItem(stringResource(R.string.carlink_about_lic_bc_name), stringResource(R.string.carlink_about_lic_bc))
    CreditItem(stringResource(R.string.carlink_about_lic_protobuf_name), stringResource(R.string.carlink_about_lic_protobuf))
    CreditItem(stringResource(R.string.carlink_about_lic_glide_name), stringResource(R.string.carlink_about_lic_glide))
    CreditItem(stringResource(R.string.carlink_about_lic_slf4j_name), stringResource(R.string.carlink_about_lic_slf4j))
    CreditItem(stringResource(R.string.carlink_about_lic_apache_name), stringResource(R.string.carlink_about_lic_apache))
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.carlink_about_lic_others), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.CreditsContent() {
    CreditItem(stringResource(R.string.carlink_about_lic_diplay_name), stringResource(R.string.carlink_about_credit_diplay))
    CreditItem(stringResource(R.string.carlink_about_lic_diauto_name), stringResource(R.string.carlink_about_credit_diauto))
    CreditItem(stringResource(R.string.carlink_about_credit_openheadunit_name), stringResource(R.string.carlink_about_credit_openheadunit))
    CreditItem(stringResource(R.string.carlink_about_credit_headunit_name), stringResource(R.string.carlink_about_credit_headunit))
    CreditItem(stringResource(R.string.carlink_about_credit_bydmate_name), stringResource(R.string.carlink_about_credit_bydmate))
    CreditItem(stringResource(R.string.carlink_about_credit_symbols_name), stringResource(R.string.carlink_about_credit_symbols))
}

@Composable
private fun licenseTitle(path: String): String = stringResource(when (path) {
    AppInfo.AGPL_ASSET -> R.string.carlink_about_read_agpl
    AppInfo.GPL_ASSET -> R.string.carlink_about_read_gpl
    else -> R.string.carlink_about_read_bydmate
})

/** A full license, reflowed into paragraphs and laid out lazily (the AGPL is ~650 lines). */
@Composable
private fun ColumnScope.LicenseText(path: String) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null, path) {
        value = withContext(Dispatchers.IO) { AppInfo.readAsset(context, path)?.let(AppInfo::paragraphs).orEmpty() }
    }
    val list = paragraphs ?: return
    if (list.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowGlyph(R.drawable.carlink_ic_info, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.carlink_about_text_unavailable), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(list) { paragraph ->
            Text(paragraph, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

// ---- Previews -----------------------------------------------------------------------------

internal val previewFacts = AboutFacts("1.0.0", 10000, "0.2.15", "0.3.11", "BYD", "DiLink5", "10", "1920×1080")

/** A release build that checked two hours ago. */
internal val previewUpdates = AboutUpdatesUi(supported = true, state = UpdateState.Idle, autoCheck = true,
    lastCheck = 1_760_000_000_000L - 2 * 60 * 60 * 1000L, now = 1_760_000_000_000L)

@Preview(name = "Car landscape", widthDp = 1440, heightDp = 810)
@Composable
private fun AboutCarPreview() {
    CarLinkTheme(profile = UiProfile.CAR) { AboutContent(previewFacts, {}) }
}

@Preview(name = "Phone portrait", widthDp = 400, heightDp = 860)
@Composable
private fun AboutPhonePreview() {
    CarLinkTheme(profile = UiProfile.HANDHELD) { AboutContent(previewFacts, {}) }
}
