package com.r3dzf.reader
import org.junit.Test
import org.junit.Assert.*
class UpdateRulesTest {
    private val full=UpdateAsset("https://github.com/R3Dzf/Reader/releases/download/v1/Rafolio.apk",1000,"a".repeat(64))
    @Test fun exactBaseChoosesSmallerDelta() {val delta=UpdateAsset(full.url,100,"c".repeat(64));val r=UpdateRelease(10,"1",26,"",full,listOf(UpdateDelta("b".repeat(64),delta)));assertEquals(delta,UpdateProtocol.deltaFor(r,"b".repeat(64))) }
    @Test fun differentBaseRequiresFull() {val r=UpdateRelease(10,"1",26,"",full,listOf(UpdateDelta("b".repeat(64),full.copy(bytes=100))));assertNull(UpdateProtocol.deltaFor(r,"d".repeat(64))) }
    @Test fun InefficientDeltaRequiresFull() {val r=UpdateRelease(10,"1",26,"",full,listOf(UpdateDelta("b".repeat(64),full.copy(bytes=900))));assertNull(UpdateProtocol.deltaFor(r,"b".repeat(64))) }
    @Test fun assetUrlIsRestrictedToRepositoryReleases() {
        assertTrue(UpdateProtocol.validAssetUrl(full.url))
        listOf("http://github.com/R3Dzf/Reader/releases/download/v1/a.apk","https://github.com/evil/Reader/releases/download/v1/a.apk","https://github.com@evil.test/R3Dzf/Reader/releases/download/v1/a.apk","https://github.com/R3Dzf/Reader/releases/download/v1/../a.apk","https://github.com/R3Dzf/Reader/releases/download/v1/a.apk?token=x").forEach {assertFalse(it,UpdateProtocol.validAssetUrl(it))}
    }
    @Test fun redirectsRejectCleartextCredentialsAndOtherHosts() {
        assertTrue(UpdateProtocol.validTransferUrl("https://release-assets.githubusercontent.com/path?token=value"))
        listOf("http://release-assets.githubusercontent.com/path","https://github.com.evil.test/x","https://user:pass@github.com/x","https://localhost/x","https://github.com:8443/x").forEach {assertFalse(it,UpdateProtocol.validTransferUrl(it))}
    }
}
