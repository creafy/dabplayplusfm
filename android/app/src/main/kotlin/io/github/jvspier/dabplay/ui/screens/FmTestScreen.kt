package io.github.jvspier.dabplay.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.ArrowLeft
import io.github.jvspier.dabplay.DabApp
import io.github.jvspier.dabplay.fyt.FytRadio
import io.github.jvspier.dabplay.ui.ScreenTitle
import io.github.jvspier.dabplay.ui.components.SquareButton
import io.github.jvspier.dabplay.ui.components.pressable
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors

/**
 * Settings → FM test: does the head unit's MainServer take commands from this app? Connects while open, shows what the
 * server reports and every event (also in the diagnostics log, for Save / Share).
 */
@Composable
fun FmTestScreen(compact: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as DabApp
    val fm = app.fmRadio
    val st by fm.state.collectAsState()
    val lines by app.fmLog.collectAsState()
    val c = LocalDabColors.current
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        app.fmEvent("FM test opened; MainServer ${if (fm.available) "installed" else "NOT installed"}; ${app.fytProps()}")
        fm.connect()
        onDispose { }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareButton(PhosphorIcons.Fill.ArrowLeft, "Back", 64.dp, onBack)
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(1f)) { ScreenTitle("FM test", compact) }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(18.dp)) {
                    Text(if (st.freq > 0) FytRadio.fmt(st.freq) else "–", color = c.text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
                    Text(st.ps.ifEmpty { "no station name (RDS)" }, color = if (st.ps.isEmpty()) c.neutral400 else c.text, fontSize = 28.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(st.radioText.ifEmpty { " " }, color = c.neutral300, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(listOf(
                        if (st.connected) "connected" else "not connected",
                        "source ${st.appId}${if (st.sourceIsRadio) " = radio" else ""}",
                        if (st.stereo) "stereo" else "mono",
                        if (st.search != 0) "searching (${st.search})" else null,
                        if (st.rds) "RDS" else null, if (st.af) "AF" else null, if (st.ta) "TA" else null, if (st.tp) "TP" else null,
                    ).filterNotNull().joinToString(" · "), color = if (st.connected) Accent.green else c.warn, fontSize = 17.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TestButton("Use FM", Accent.orange, Modifier.weight(1f)) { app.engine.pause(); fm.requestSource() }
                    TestButton("Back to DAB", Accent.blue, Modifier.weight(1f)) { fm.releaseSource(); app.engine.resume() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TestButton("◀◀ Seek", null, Modifier.weight(1f)) { fm.seekDown() }
                    TestButton("−", null, Modifier.weight(0.6f)) { fm.stepDown() }
                    TestButton("+", null, Modifier.weight(0.6f)) { fm.stepUp() }
                    TestButton("Seek ▶▶", null, Modifier.weight(1f)) { fm.seekUp() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TestButton("FM band", null, Modifier.weight(1f)) { fm.bandFm() }
                    TestButton("101.70", null, Modifier.weight(1f)) { fm.tune(10170) }
                    TestButton("AF", null, Modifier.weight(0.7f)) { fm.toggleAf() }
                    TestButton("TA", null, Modifier.weight(0.7f)) { fm.toggleTa() }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(12.dp),
                reverseLayout = true) {
                items(lines.asReversed()) { line ->
                    Text(line, color = c.neutral300, fontSize = 14.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun TestButton(label: String, accent: Color?, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalDabColors.current
    Box(modifier.height(72.dp).clip(RoundedCornerShape(14.dp)).background(accent?.copy(alpha = 0.18f) ?: c.surface).pressable(onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
