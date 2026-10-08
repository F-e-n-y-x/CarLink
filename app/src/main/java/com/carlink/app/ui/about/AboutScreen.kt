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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.diagnostics.AppInfo
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.components.CarIcon
import com.carlink.app.ui.components.CarSheet
import com.carlink.app.ui.components.CarTopBar
import com.carlink.app.ui.components.CreditItem
import com.carlink.app.ui.components.NavigationRow
import com.carlink.app.ui.components.QrPanel
import com.carlink.app.ui.components.RowDivider
import com.carlink.app.ui.components.SectionCard
import com.carlink.app.ui.components.SectionHeader
import com.carlink.app.ui.components.SettingRow
import com.carlink.app.ui.components.SubHeading
import com.carlink.app.ui.components.ltr
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WidthClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which parked-reading sheet About shows. */
private enum class AboutSheet { NONE, LICENSES, CREDITS }

/** Facts About shows; gathered once so previews can supply their own. */
private data class AboutFacts(
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
 * About (DESIGN_SPEC §6.6): identity, versions, device, licenses and credits, and the source as
 * QR codes (links are never typed in a car). Two columns from EXPANDED width (identity and QR |
 * information), one scrolling column otherwise.
 */
@Composable
fun AboutScreen(vm: CarLinkViewModel, onBack: () -> Unit) {
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
    AboutContent(facts, onBack)
}

@Composable
private fun AboutContent(facts: AboutFacts, onBack: () -> Unit) {
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens
    val margin = if (layout.width == WidthClass.COMPACT) dimens.compactMargin else dimens.screenMargin
    var sheet by rememberSaveable { mutableStateOf(AboutSheet.NONE) }
    var licenseText by rememberSaveable { mutableStateOf<String?>(null) }

    // A Surface sets the content color, so text that doesn't name one is onBackground, not black.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize()) {
        CarTopBar(stringResource(R.string.carlink_about_title), onBack = onBack)
        if (layout.width >= WidthClass.EXPANDED) {
            Row(
                Modifier.fillMaxSize().widthIn(max = 1600.dp).align(Alignment.CenterHorizontally).padding(horizontal = margin),
                horizontalArrangement = Arrangement.spacedBy(dimens.gutter * 2),
            ) {
                Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(bottom = dimens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(dimens.gutter))
                    Identity(centered = true)
                    SourceCodes(centered = true)
                }
                Column(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(bottom = dimens.gutter)) {
                    InfoSections(facts, onLicenses = { sheet = AboutSheet.LICENSES }, onCredits = { sheet = AboutSheet.CREDITS })
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(horizontal = margin).padding(bottom = dimens.gutter)) {
                    Spacer(Modifier.height(if (layout.width == WidthClass.COMPACT) 8.dp else dimens.gutter))
                    // A short landscape phone keeps the mark beside the name to save height.
                    Identity(centered = false)
                    InfoSections(facts, onLicenses = { sheet = AboutSheet.LICENSES }, onCredits = { sheet = AboutSheet.CREDITS })
                    SourceCodes(centered = false)
                }
            }
        }
    } }

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

// ---- Identity and source ----------------------------------------------------------------

@Composable
private fun Identity(centered: Boolean) {
    val dimens = CarLinkTokens.dimens
    val markSize = dimens.avatarHero * 1.25f
    // The mark is an adaptive-icon foreground: the glyph fills about half of its canvas, so draw
    // the canvas at twice the mark size and let the empty margin overflow.
    val mark = @Composable {
        Box(Modifier.size(markSize), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.carlink_icon_foreground), null, Modifier.requiredSize(markSize * 2),
                tint = Color.Unspecified)
        }
    }
    if (centered) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            mark()
            Spacer(Modifier.height(dimens.targetGap))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.carlink_about_tagline), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    } else {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            mark()
            Spacer(Modifier.width(dimens.gutter))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.carlink_about_tagline), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceCodes(centered: Boolean) {
    val dimens = CarLinkTokens.dimens
    val qrSize: Dp = if (CarLinkTokens.profile == UiProfile.CAR) 240.dp else 168.dp
    SectionHeader(stringResource(R.string.carlink_about_source), subtitle = stringResource(R.string.carlink_about_source_body))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (centered) Alignment.TopCenter else Alignment.TopStart) {
        // Text below each code is wider than the code at large font scales; allow for it.
        val sideBySide = maxWidth >= qrSize * 2 + dimens.gutter * 2
        val panels = @Composable {
            QrPanel(stringResource(R.string.carlink_about_qr_carplay), AppInfo.CARPLAY_ENGINE_SOURCE, qrSize)
            QrPanel(stringResource(R.string.carlink_about_qr_aa), AppInfo.ANDROID_AUTO_ENGINE_SOURCE, qrSize)
        }
        if (sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gutter * 2), verticalAlignment = Alignment.Top) { panels() }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimens.gutter),
                horizontalAlignment = Alignment.CenterHorizontally) { panels() }
        }
    }
}

// ---- Information ------------------------------------------------------------------------

@Composable
private fun InfoSections(facts: AboutFacts, onLicenses: () -> Unit, onCredits: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    Spacer(Modifier.height(dimens.gutter))
    SectionCard {
        SettingRow(stringResource(R.string.carlink_about_version), icon = R.drawable.carlink_ic_info,
            supporting = ltr(stringResource(R.string.carlink_about_version_value, facts.versionName, facts.versionCode)))
        RowDivider()
        SettingRow(stringResource(R.string.carlink_about_carplay_engine), icon = R.drawable.carlink_ic_directions_car,
            supporting = stringResource(R.string.carlink_about_carplay_engine_value, ltr(facts.carPlayEngine)))
        RowDivider()
        SettingRow(stringResource(R.string.carlink_about_aa_engine), icon = R.drawable.carlink_ic_directions_car,
            supporting = stringResource(R.string.carlink_about_aa_engine_value, ltr(facts.androidAutoEngine)))
        RowDivider()
        SettingRow(stringResource(R.string.carlink_about_device), icon = R.drawable.carlink_ic_devices,
            supporting = stringResource(R.string.carlink_about_device_detail, ltr(facts.manufacturer), ltr(facts.model),
                ltr(facts.androidRelease), ltr(facts.screen)))
    }
    Spacer(Modifier.height(dimens.gutter))
    SectionCard {
        NavigationRow(stringResource(R.string.carlink_about_licenses), onLicenses,
            supporting = stringResource(R.string.carlink_about_licenses_body), icon = R.drawable.carlink_ic_gavel)
        RowDivider()
        NavigationRow(stringResource(R.string.carlink_about_credits), onCredits,
            supporting = stringResource(R.string.carlink_about_credits_body), icon = R.drawable.carlink_ic_groups)
    }
    SectionHeader(stringResource(R.string.carlink_about_legal))
    LegalText(stringResource(R.string.carlink_about_trademarks))
    LegalText(stringResource(R.string.carlink_about_not_certified))
    LegalText(stringResource(R.string.carlink_about_byd))
}

@Composable
private fun LegalText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp))
}

// ---- Sheets -----------------------------------------------------------------------------

@Composable
private fun ColumnScope.LicensesContent(onRead: (String) -> Unit) {
    Text(stringResource(R.string.carlink_about_licenses_intro), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.carlink_about_licenses_source), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    SubHeading(stringResource(R.string.carlink_about_full_texts))
    SectionCard {
        NavigationRow(stringResource(R.string.carlink_about_read_agpl), { onRead(AppInfo.AGPL_ASSET) },
            icon = R.drawable.carlink_ic_description)
        RowDivider()
        NavigationRow(stringResource(R.string.carlink_about_read_gpl), { onRead(AppInfo.GPL_ASSET) },
            icon = R.drawable.carlink_ic_description)
        RowDivider()
        NavigationRow(stringResource(R.string.carlink_about_read_bydmate), { onRead(AppInfo.BYDMATE_ASSET) },
            icon = R.drawable.carlink_ic_description)
    }

    SubHeading(stringResource(R.string.carlink_about_components))
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
            CarIcon(R.drawable.carlink_ic_info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
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

// ---- Previews ---------------------------------------------------------------------------

private val previewFacts = AboutFacts("0.1.0", 1, "0.2.15", "0.3.11", "BYD", "DiLink5", "10", "1920×1080")

@Preview(name = "Car landscape", widthDp = 1440, heightDp = 810)
@Composable
private fun AboutCarPreview() {
    CarLinkTheme(profile = UiProfile.CAR) { AboutContent(previewFacts) {} }
}

@Preview(name = "Phone portrait", widthDp = 400, heightDp = 860)
@Composable
private fun AboutPhonePreview() {
    CarLinkTheme(profile = UiProfile.HANDHELD) { AboutContent(previewFacts) {} }
}
