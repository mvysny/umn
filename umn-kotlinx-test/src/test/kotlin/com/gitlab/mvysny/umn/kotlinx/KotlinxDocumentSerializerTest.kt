package com.gitlab.mvysny.umn.kotlinx

import com.gitlab.mvysny.umn.core.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.serializer
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.expect

// The README's example, verbatim: keep the two in step.
@OptIn(ExperimentalSerializationApi::class)
object ProtoBufDocumentSerializer : DocumentSerializer {
    override fun serialize(document: Any, dout: DataOutputStream) {
        dout.write(ProtoBuf.encodeToByteArray(serializer(document.javaClass), document))
    }

    override fun <T : Any> deserialize(clazz: KClass<T>, din: DataInputStream): T {
        // the document is the stream's last item, with no length in front: read it to the end
        val document = ProtoBuf.decodeFromByteArray(serializer(clazz.java), din.readBytes())
        return clazz.javaObjectType.cast(document)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Note(@ProtoNumber(1) val title: String, @ProtoNumber(2) val stars: Int = 0)

class KotlinxDocumentSerializerTest {
    @Test fun `a document survives the store`() {
        val file = File.createTempFile("kotlinx", ".mv").absolutePath
        val id = MvstoreUMN(MVStore.open(file), ProtoBufDocumentSerializer, AutoTypeResolver).use { umn ->
            umn.create(Note("milk", 3))
        }
        MvstoreUMN(MVStore.open(file), ProtoBufDocumentSerializer, AutoTypeResolver).use { umn ->
            expect(Note("milk", 3)) { umn.get(id, Note::class) }
        }
    }

    @Test fun `a document survives the sync log, where it ends the record`() {
        val id = UUID.randomUUID()
        val type = AutoTypeResolver.toType(Note::class)
        for (record in listOf(LogRecord.DocumentCreated(Note("milk"), type, id), LogRecord.DocumentUpdated(Note("eggs", 5), type, id))) {
            val bytes = ByteArrayOutputStream().also { record.writeTo(DataOutputStream(it), ProtoBufDocumentSerializer) }.toByteArray()
            expect(record) { LogRecord.fromByteArray(bytes, ProtoBufDocumentSerializer, AutoTypeResolver) }
        }
    }
}
