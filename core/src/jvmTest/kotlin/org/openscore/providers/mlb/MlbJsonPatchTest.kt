package org.openscore.providers.mlb

import kotlinx.serialization.json.JsonArray
import org.openscore.net.OpenScoreJson
import kotlin.test.Test
import kotlin.test.assertEquals

class MlbJsonPatchTest {
    @Test
    fun appliesTheRfc6902OperationsUsedByTheMlbFeed() {
        val document = OpenScoreJson.parseToJsonElement(
            """{"metaData":{"timeStamp":"old"},"plays":[{"id":1},{"id":2}],"labels":{"a/b":"old"}}""",
        )
        val patches = OpenScoreJson.parseToJsonElement(
            """
            [{"diff":[
              {"op":"replace","path":"/metaData/timeStamp","value":"new"},
              {"op":"add","path":"/plays/1/event","value":"strike"},
              {"op":"add","path":"/plays/-","value":{"id":3}},
              {"op":"remove","path":"/plays/0"},
              {"op":"copy","from":"/plays/0/event","path":"/labels/copied"},
              {"op":"move","from":"/labels/a~1b","path":"/labels/renamed"},
              {"op":"test","path":"/labels/renamed","value":"old"}
            ]}]
            """.trimIndent(),
        ) as JsonArray

        val actual = MlbJsonPatch.apply(document, patches)
        val expected = OpenScoreJson.parseToJsonElement(
            """{"metaData":{"timeStamp":"new"},"plays":[{"id":2,"event":"strike"},{"id":3}],"labels":{"copied":"strike","renamed":"old"}}""",
        )
        assertEquals(expected, actual)
    }
}
