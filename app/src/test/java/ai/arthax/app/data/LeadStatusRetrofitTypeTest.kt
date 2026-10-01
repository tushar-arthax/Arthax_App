package ai.arthax.app.data

import ai.arthax.app.data.remote.api.ArthaxApi
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * That Retrofit can still see the element type of the endpoints returning a bare array.
 *
 * Retrofit does not read the Kotlin signature. For a suspend function it reads the generic
 * Java one off the `Continuation` parameter, and that only survives while the `Signature`
 * attribute does — strip it and `Response<List<LeadOptionDto>>` erases to a raw `List`,
 * whereupon Moshi parses each element into a `LinkedHashMap` and the first field access
 * downstream throws `ClassCastException` on a response that was perfectly valid.
 *
 * Nothing in the app would report that as a parsing problem, so it is pinned here. A
 * concrete body type like `Response<CallHistoryPageDto>` cannot regress this way; only the
 * generic ones can, which is why these two endpoints get the test.
 */
class LeadStatusRetrofitTypeTest {

    /**
     * The body type the Moshi converter is asked for, resolved the way Retrofit resolves it:
     * the last parameter is `Continuation<? super Response<T>>`.
     */
    private fun responseBodyTypeOf(methodName: String): Type {
        val method = ArthaxApi::class.java.declaredMethods.single { it.name == methodName }
        val continuation = method.genericParameterTypes.last() as ParameterizedType
        val response = (continuation.actualTypeArguments[0] as WildcardType).lowerBounds[0]

        val parameterized = response as ParameterizedType
        assertEquals(Response::class.java, parameterized.rawType)
        return parameterized.actualTypeArguments[0]
    }

    @Test
    fun `the statuses body type keeps its element type and parses a live response`() {
        val bodyType = responseBodyTypeOf("getLeadStatuses")

        // Raw `java.util.List` here is the regression this test exists for.
        assertEquals(
            "java.util.List<ai.arthax.app.data.remote.dto.LeadOptionDto>",
            bodyType.toString(),
        )

        val parsed = Moshi.Builder().build().adapter<Any>(bodyType).fromJson(LIVE)
        assertNotNull(parsed)
        assertEquals(7, (parsed as List<*>).size)

        // Parsed into the DTO, not into maps, which is what erasure would silently give.
        assertEquals(
            "ai.arthax.app.data.remote.dto.LeadOptionDto",
            parsed.first()!!::class.java.name,
        )
    }

    @Test
    fun `the sources body type keeps its element type too`() {
        assertEquals(
            "java.util.List<ai.arthax.app.data.remote.dto.LeadOptionDto>",
            responseBodyTypeOf("getLeadSources").toString(),
        )
    }

    private companion object {
        val LIVE = """
        [
          {"id": "11111111-1111-4111-8111-111111111111", "name": "new", "is_default": true},
          {"id": "22222222-2222-4222-8222-222222222222", "name": "contacted", "is_default": false},
          {"id": "33333333-3333-4333-8333-333333333333", "name": "qualified", "is_default": false},
          {"id": "44444444-4444-4444-8444-444444444444", "name": "proposal", "is_default": false},
          {"id": "55555555-5555-4555-8555-555555555555", "name": "negotiation", "is_default": false},
          {"id": "66666666-6666-4666-8666-666666666666", "name": "won", "is_default": false},
          {"id": "77777777-7777-4777-8777-777777777777", "name": "lost", "is_default": false}
        ]
        """.trimIndent()
    }
}
