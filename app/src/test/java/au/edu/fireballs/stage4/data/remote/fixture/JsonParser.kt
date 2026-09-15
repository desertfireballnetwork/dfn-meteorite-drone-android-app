package au.edu.fireballs.stage4.data.remote.fixture

import com.squareup.moshi.JsonReader
import okio.Buffer

object JsonParser {
    fun parse(json: String): Any? {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        return readValue(reader)
    }

    private fun readValue(reader: JsonReader): Any? =
        when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> readObject(reader)
            JsonReader.Token.BEGIN_ARRAY -> readArray(reader)
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> readNumber(reader)
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> {
                reader.nextNull<Any>()
                null
            }
            else -> error("Unexpected JSON token ${reader.peek()}")
        }

    private fun readObject(reader: JsonReader): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            result[reader.nextName()] = readValue(reader)
        }
        reader.endObject()
        return result
    }

    private fun readArray(reader: JsonReader): List<Any?> {
        val result = mutableListOf<Any?>()
        reader.beginArray()
        while (reader.hasNext()) {
            result.add(readValue(reader))
        }
        reader.endArray()
        return result
    }

    private fun readNumber(reader: JsonReader): Any {
        val raw = reader.nextString()
        return if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
            raw.toDouble()
        } else {
            raw.toLong()
        }
    }
}
