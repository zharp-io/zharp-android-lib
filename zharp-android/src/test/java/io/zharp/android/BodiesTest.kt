package io.zharp.android

import io.zharp.android.internal.Bodies
import io.zharp.android.internal.BodySettings
import io.zharp.android.internal.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared body and URL rules, case by case (the same cases as the JavaScript SDK's). */
class BodiesTest {
    private val settings = BodySettings(all = false, maxSize = 1000, extraKeys = listOf("dateOfBirth"), urls = emptyList())

    private fun format(text: String, type: String? = "application/json", cut: Boolean = false) =
        Bodies.formatBody(text, type, settings, cut)!!

    @Test
    fun secretKeysAreFilteredAndTheirNamesKept() {
        val (value, truncated) = format("""{"email":"a@b.test","password":"hunter2","dateOfBirth":"1990-01-01","remember":true,"n":1.50}""")
        assertEquals("""{"email":"a@b.test","password":"[Filtered]","dateOfBirth":"[Filtered]","remember":true,"n":1.50}""", value)
        assertFalse(truncated)
    }

    @Test
    fun keysAreMatchedIgnoringCaseDashesAndUnderscores() {
        for (key in listOf("Password", "api_key", "X-Auth-Token", "accessKeyId", "client_secret", "card", "CVV", "session_id", "auth", "pwd", "PWD")) {
            assertTrue(key, Bodies.isSecretKey(key))
        }
        for (key in listOf("author", "key", "name", "cardinality", "pinned", "state", "session", "pwdx")) {
            assertFalse(key, Bodies.isSecretKey(key))
        }
        assertTrue(Bodies.isSecretKey("date_of_birth", listOf("dateOfBirth")))
    }

    @Test
    fun aSecretObjectKeepsItsKeysWithEveryValueFiltered() {
        val (value) = format("""{"card":{"brand":"visa","last4":"4242","tags":["a",1]},"empty":{"token":""},"none":{"secret":null}}""")
        assertEquals(
            """{"card":{"brand":"[Filtered]","last4":"[Filtered]","tags":["[Filtered]","[Filtered]"]},"empty":{"token":""},"none":{"secret":null}}""",
            value,
        )
    }

    @Test
    fun nestedObjectsAndArraysAreWalked() {
        val (value) = format("""[{"user":{"apiKey":"k-123","email":"x@y.z"}},{"items":[{"otp":"123456"}]}]""")
        assertEquals("""[{"user":{"apiKey":"[Filtered]","email":"x@y.z"}},{"items":[{"otp":"[Filtered]"}]}]""", value)
    }

    @Test
    fun formsAreRedacted() {
        val (value) = format("user=ada&password=p4ss&x", "application/x-www-form-urlencoded")
        assertEquals("user=ada&password=[Filtered]&x", value)
    }

    @Test
    fun inlineImagesAndLongBase64BecomeAShortDescription() {
        val pixel = "data:image/png;base64," + "iVBORw0KGgo".repeat(4000)
        val blob = "QUJD".repeat(500) + "1"
        val (value) = format(Json.write(linkedMapOf("avatar" to pixel, "blob" to blob, "words" to "a".repeat(300), "name" to "Ada")))
        val sent = Json.parseOrNull(value) as Map<*, *>
        assertEquals("[image/png · 32 KB]", sent["avatar"])
        assertEquals("[base64 · 1 KB]", sent["blob"])
        assertEquals("long text that isn't base64 stays", "a".repeat(300), sent["words"])
        assertEquals("Ada", sent["name"])
    }

    @Test
    fun textThatDoesNotParseIsRedactedWhereItCan() {
        val cutOff = """{"token":"abc","avatar":"data:image/png;base64,${"iVBORw0KGgo".repeat(100)}"""
        val (value, truncated) = format(cutOff, cut = true)
        assertEquals("""{"token":"[Filtered]","avatar":"[image/png · 825 B+]"""", value)
        assertTrue(truncated)
    }

    @Test
    fun longBodiesAreCutAtMaxSize() {
        val (value, truncated) = format("x".repeat(5000), "text/plain")
        assertEquals(1000, value.length)
        assertTrue(truncated)
    }

    @Test
    fun bodyAttributesCarryTheTypeAndTheTruncatedFlag() {
        val a = Bodies.bodyAttributes(Bodies.Side.RESPONSE, "y".repeat(2000), "text/plain; charset=utf-8", settings)
        assertEquals(1000, (a["http.response.body"] as String).length)
        assertEquals(true, a["http.response.body.truncated"])
        assertEquals("text/plain; charset=utf-8", a["http.response.header.content-type"])
        assertTrue(Bodies.bodyAttributes(Bodies.Side.REQUEST, "   ", "text/plain", settings).isEmpty())
        assertTrue(Bodies.bodyAttributes(Bodies.Side.REQUEST, "abc", "image/png", settings).isEmpty())
    }

    @Test
    fun bodiesThatAreNotTextAreDescribed() {
        assertEquals("[binary · image/png · 3 KB]", Bodies.describeBody("image/png", 3000))
        assertEquals("[binary · application/pdf · 1.2 MB]", Bodies.describeBody("application/pdf; x=1", 1_258_291))
        assertEquals("[binary]", Bodies.describeBody(null))
        assertEquals("[event stream]", Bodies.describeBody("text/event-stream"))
        assertEquals("[file photo.png · image/png · 240 KB]", Bodies.fileLabel("photo.png", "image/png", 240 * 1024L))
        assertEquals("[file blob · 12 B+]", Bodies.fileLabel(null, null, 12, partial = true))
        assertEquals("[binary · image/jpeg]", Bodies.describedAttributes(Bodies.Side.RESPONSE, "image/jpeg")["http.response.body"])
    }

    @Test
    fun sizesRoundAsTheJavaScriptSdkDoes() {
        assertEquals("1023 B", Bodies.formatSize(1023))
        assertEquals("1 KB", Bodies.formatSize(1500))
        assertEquals("2 KB", Bodies.formatSize(1536))
        assertEquals("1.0 MB", Bodies.formatSize(1024 * 1024))
    }

    @Test
    fun textualTypes() {
        for (t in listOf(null, "application/json", "application/problem+json", "text/html", "application/xml", "application/x-www-form-urlencoded", "application/vnd.api+json")) {
            assertTrue("$t", Bodies.isTextual(t))
        }
        for (t in listOf("image/png", "application/octet-stream", "text/event-stream", "multipart/form-data", "application/pdf")) {
            assertFalse(t, Bodies.isTextual(t))
        }
    }

    @Test
    fun multipartSummariesAreFormattedAsJson() {
        val a = Bodies.bodyAttributes(
            Bodies.Side.REQUEST,
            """{"title":"Holiday","password":"x","photo":"[file p.png · image/png · 2 KB]"}""",
            "multipart/form-data",
            settings,
        )
        assertEquals("""{"title":"Holiday","password":"[Filtered]","photo":"[file p.png · image/png · 2 KB]"}""", a["http.request.body"])
        assertEquals("multipart/form-data", a["http.request.header.content-type"])
    }

    @Test
    fun wantedFollowsTheMode() {
        assertFalse(settings.wanted(200))
        assertTrue(settings.wanted(404))
        assertTrue(settings.wanted(0))
        assertTrue(BodySettings(all = true, maxSize = 1, extraKeys = emptyList(), urls = emptyList()).wanted(200))
        assertEquals(64 * 1024, settings.rawLimit)
        assertEquals(BodySettings.DEFAULT_MAX, BodySettings.clampSize(0))
        assertEquals(BodySettings.MAX, BodySettings.clampSize(10_000_000))
    }

    @Test
    fun urlQueryValuesThatLookSecretAreFiltered() {
        assertEquals("https://api.x.test/p?token=[Filtered]&page=2", Bodies.redactUrl("https://api.x.test/p?token=abc&page=2"))
        assertEquals("https://api.x.test/p?key=[Filtered]&sig=[Filtered]&code=[Filtered]#frag", Bodies.redactUrl("https://user:pw@api.x.test/p?key=1&sig=2&code=3#frag"))
        assertEquals("https://api.x.test/p?empty=&token=", Bodies.redactUrl("https://api.x.test/p?empty=&token="))
        assertEquals("q=shoes&api_key=[Filtered]", Bodies.redactQuery("q=shoes&api_key=s3cr3t"))
        assertEquals("pwd=[Filtered]&session=[Filtered]&sessions=3", Bodies.redactQuery("pwd=x&session=abc&sessions=3"))
        assertEquals("""{"pwd":"[Filtered]","session":"abc"}""", Bodies.formatBody("""{"pwd":"x","session":"abc"}""", "application/json", settings)!!.first)
    }

    @Test
    fun jsonRoundTripsUnicodeAndEscapes() {
        val text = """{"a":"line\nbreak \"quoted\" é \u0001","b":[true,false,null,-1.5e3]}"""
        assertEquals("""{"a":"line\nbreak \"quoted\" é \u0001","b":[true,false,null,-1.5e3]}""", Json.write(Json.parseOrNull(text)))
        assertTrue(Json.parseOrNull("{\"a\":") === Json.NOT_JSON)
    }
}
