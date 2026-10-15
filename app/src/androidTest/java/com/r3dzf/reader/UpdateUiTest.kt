package com.r3dzf.reader
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
class UpdateUiTest {
    @get:Rule val compose=createComposeRule()
    private val full=UpdateAsset("https://github.com/R3Dzf/Reader/releases/download/v1/full.apk",52_000_000,"a".repeat(64))
    private val delta=full.copy(bytes=1_000_000)
    private val release=UpdateRelease(10,"0.9.1",26,"",full,emptyList())
    private fun show(status: UpdateStatus,download: ()->Unit={},fullDownload: ()->Unit={},cancel: ()->Unit={}) {compose.setContent {ReaderTheme {UpdateScreen(status,{},download,fullDownload,{},cancel,{})}}}
    @Test fun deltaShowsActualSizeAndUserStartsDownload() {var clicked=false;show(UpdateStatus(UpdatePhase.AVAILABLE,release,delta),download={clicked=true});compose.onNodeWithTag("update-size").assertIsDisplayed();compose.onNodeWithTag("download-update").performClick();compose.runOnIdle {assertTrue(clicked)}}
    @Test fun fallbackRequiresAnExplicitFullDownload() {var clicked=false;show(UpdateStatus(UpdatePhase.FULL_REQUIRED,release,delta),fullDownload={clicked=true});compose.onNodeWithTag("download-update").assertDoesNotExist();compose.runOnIdle {assertFalse(clicked)};compose.onNodeWithTag("download-full-update").performClick();compose.runOnIdle {assertTrue(clicked)}}
    @Test fun progressOffersCancellation() {var cancelled=false;show(UpdateStatus(UpdatePhase.DOWNLOADING,release,delta,500_000,1_000_000),cancel={cancelled=true});compose.onNodeWithTag("update-progress").assertIsDisplayed();compose.onNodeWithTag("cancel-update").performClick();compose.runOnIdle {assertTrue(cancelled)}}
    @Test fun onlyVerifiedReadyStateOffersInstall() {show(UpdateStatus(UpdatePhase.READY,release));compose.onNodeWithTag("install-update").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("download-update").assertDoesNotExist()}
}
