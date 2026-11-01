package online.bottleneck.adfree.core

/** The parts of a DNS query this app needs: its id, the single question, and where it ends. */
data class DnsQuestion(
    val id: Int,
    val name: String,
    val type: Int,
    val qclass: Int,
    /** Offset just past the question section in the original message. */
    val questionEnd: Int,
)

object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1
    private const val HEADER = 12
    private const val BLOCKED_TTL = 60

    /** Parses a standard query with exactly one question. Returns null for anything else. */
    fun parseQuery(msg: ByteArray, offset: Int = 0, length: Int = msg.size - offset): DnsQuestion? {
        if (length < HEADER + 5) return null
        val end = offset + length
        val flags = u16(msg, offset + 2)
        val isResponse = flags and 0x8000 != 0
        val opcode = (flags shr 11) and 0xF
        if (isResponse || opcode != 0) return null
        if (u16(msg, offset + 4) != 1) return null

        val labels = ArrayList<String>()
        var pos = offset + HEADER
        var nameLength = 0
        while (true) {
            if (pos >= end) return null
            val len = msg[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if (len and 0xC0 != 0) return null // compression pointers are not valid in a lone question
            if (pos + len > end) return null
            nameLength += len + 1
            if (nameLength > 255) return null
            labels.add(String(msg, pos, len, Charsets.US_ASCII))
            pos += len
        }
        if (pos + 4 > end) return null
        val type = u16(msg, pos)
        val qclass = u16(msg, pos + 2)
        return DnsQuestion(
            id = u16(msg, offset),
            name = labels.joinToString(".").lowercase(),
            type = type,
            qclass = qclass,
            questionEnd = pos + 4 - offset,
        )
    }

    /**
     * Builds the answer for a blocked domain. A and AAAA queries get `0.0.0.0` / `::`,
     * which apps treat as an unreachable server; other types get an empty answer.
     */
    fun blockedResponse(query: ByteArray, offset: Int, question: DnsQuestion): ByteArray {
        val answerData = when {
            question.qclass != CLASS_IN -> null
            question.type == TYPE_A -> ByteArray(4)
            question.type == TYPE_AAAA -> ByteArray(16)
            else -> null
        }
        val questionLength = question.questionEnd - HEADER
        val answerLength = if (answerData == null) 0 else 12 + answerData.size
        val out = ByteArray(HEADER + questionLength + answerLength)

        val queryFlags = u16(query, offset + 2)
        val rd = queryFlags and 0x0100
        put16(out, 0, question.id)
        put16(out, 2, 0x8000 or rd or 0x0080) // QR, copy RD, RA, NOERROR
        put16(out, 4, 1)
        put16(out, 6, if (answerData == null) 0 else 1)
        // NSCOUNT and ARCOUNT stay 0: any EDNS record in the query is dropped.
        System.arraycopy(query, offset + HEADER, out, HEADER, questionLength)

        if (answerData != null) {
            var p = HEADER + questionLength
            put16(out, p, 0xC00C); p += 2 // pointer to the name in the question
            put16(out, p, question.type); p += 2
            put16(out, p, CLASS_IN); p += 2
            put16(out, p, 0); put16(out, p + 2, BLOCKED_TTL); p += 4
            put16(out, p, answerData.size); p += 2
            System.arraycopy(answerData, 0, out, p, answerData.size)
        }
        return out
    }

    internal fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    internal fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }
}
