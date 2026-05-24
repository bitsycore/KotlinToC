package ktc.std

object Sha256 {

    private val K: @Size(64) UIntArray = arrayOf(
        0x428a2f98U,
        0x71374491U,
        0xb5c0fbcfU,
        0xe9b5dba5U,
        0x3956c25bU,
        0x59f111f1U,
        0x923f82a4U,
        0xab1c5ed5U,
        0xd807aa98U,
        0x12835b01U,
        0x243185beU,
        0x550c7dc3U,
        0x72be5d74U,
        0x80deb1feU,
        0x9bdc06a7U,
        0xc19bf174U,
        0xe49b69c1U,
        0xefbe4786U,
        0x0fc19dc6U,
        0x240ca1ccU,
        0x2de92c6fU,
        0x4a7484aaU,
        0x5cb0a9dcU,
        0x76f988daU,
        0x983e5152U,
        0xa831c66dU,
        0xb00327c8U,
        0xbf597fc7U,
        0xc6e00bf3U,
        0xd5a79147U,
        0x06ca6351U,
        0x14292967U,
        0x27b70a85U,
        0x2e1b2138U,
        0x4d2c6dfcU,
        0x53380d13U,
        0x650a7354U,
        0x766a0abbU,
        0x81c2c92eU,
        0x92722c85U,
        0xa2bfe8a1U,
        0xa81a664bU,
        0xc24b8b70U,
        0xc76c51a3U,
        0xd192e819U,
        0xd6990624U,
        0xf40e3585U,
        0x106aa070U,
        0x19a4c116U,
        0x1e376c08U,
        0x2748774cU,
        0x34b0bcb5U,
        0x391c0cb3U,
        0x4ed8aa4aU,
        0x5b9cca4fU,
        0x682e6ff3U,
        0x748f82eeU,
        0x78a5636fU,
        0x84c87814U,
        0x8cc70208U,
        0x90befffaU,
        0xa4506cebU,
        0xbef9a3f7U,
        0xc67178f2U,
    )

    fun new(): Context = Context()

    fun digest(buff: @Ptr ByteArray, offset: Int, length: Int): @Size(32) ByteArray {
        val ctx = new()
        ctx.update(buff, offset, length)
        return ctx.finalizeHash()
    }

    fun digest(buff: @Ptr ByteArray): @Size(32) ByteArray {
        return digest(buff, 0, buff.size)
    }

    class Context() {
        private val state: @Size(8) UIntArray = uintArrayOf(
            0x6a09e667U,
            0xbb67ae85U,
            0x3c6ef372U,
            0xa54ff53aU,
            0x510e527fU,
            0x9b05688cU,
            0x1f83d9abU,
            0x5be0cd19U
        )

        private val buffer: @Size(64) ByteArray = ByteArray(64)
        private val w: @Size(64) IntArray = IntArray(64)

        private var bufferSize = 0
        private var totalBytes = 0L

        fun update(buff: @Ptr ByteArray) {
            return update(buff, 0, buff.size)
        }

        fun update(buff: @Ptr ByteArray, offset: Int, length: Int) {
            var off = offset
            var len = length

            totalBytes += len.toLong()

            // fill existing partial block
            if (bufferSize > 0) {
                val needed = 64 - bufferSize
                val take = if (needed < len) needed else len

                for (i in 0 until take) {
                    buffer[bufferSize + i] = buff[off + i]
                }

                bufferSize += take
                off += take
                len -= take

                if (bufferSize == 64) {
                    compress(buffer, 0)
                    bufferSize = 0
                }
            }

            // process full blocks directly from input (NO COPY)
            while (len >= 64) {
                compress(buff, off)
                off += 64
                len -= 64
            }

            // store remainder
            if (len > 0) {
                for (i in 0 until len) {
                    buffer[i] = buff[off + i]
                }
                bufferSize = len
            }
        }

        private fun compress(block: @Ptr ByteArray, offset: Int) {

            for (i in 0 until 16) {
                val j = offset + i * 4

                w[i] = ((block[j].toInt() and 0xff) shl 24) or
                        ((block[j + 1].toInt() and 0xff) shl 16) or
                        ((block[j + 2].toInt() and 0xff) shl 8) or
                        (block[j + 3].toInt() and 0xff)
            }

            for (i in 16 until 64) {
                val s0 = smallSigma0(w[i - 15])
                val s1 = smallSigma1(w[i - 2])
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]

            for (i in 0 until 64) {
                val t1 = h + bigSigma1(e) + ch(e, f, g) + K[i] + w[i]
                val t2 = bigSigma0(a) + maj(a, b, c)
                h = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }

            state[0] += a
            state[1] += b
            state[2] += c
            state[3] += d
            state[4] += e
            state[5] += f
            state[6] += g
            state[7] += h
        }

        fun finalizeHash(): @Size(32) ByteArray {
            val bitLength = totalBytes * 8L

            // append 0x80
            buffer[bufferSize++] = 0x80.toByte()

            // not enough room for length
            if (bufferSize > 56) {
                while (bufferSize < 64) {
                    buffer[bufferSize++] = 0
                }

                compress(buffer, 0)
                bufferSize = 0
            }

            // zero pad
            while (bufferSize < 56) {
                buffer[bufferSize++] = 0
            }

            // append length (big endian)
            for (i in 7 downTo 0) {
                buffer[bufferSize++] =
                    ((bitLength ushr (i * 8)) and 0xff).toByte()
            }

            compress(buffer, 0)

            val out: @Size(32) ByteArray = ByteArray(32)

            for (i in 0 until 8) {
                val v = state[i]
                val j = i * 4

                out[j] = (v ushr 24).toByte()
                out[j + 1] = (v ushr 16).toByte()
                out[j + 2] = (v ushr 8).toByte()
                out[j + 3] = v.toByte()
            }

            return out
        }
    }

    private inline fun rotr(x: Int, n: Int): Int {
        return (x ushr n) or (x shl (32 - n))
    }

    private inline fun ch(x: Int, y: Int, z: Int): Int {
        return (x and y) xor (x.inv() and z)
    }

    private inline fun maj(x: Int, y: Int, z: Int): Int {
        return (x and y) xor (x and z) xor (y and z)
    }

    private inline fun bigSigma0(x: Int): Int {
        return rotr(x, 2) xor rotr(x, 13) xor rotr(x, 22)
    }

    private inline fun bigSigma1(x: Int): Int {
        return rotr(x, 6) xor rotr(x, 11) xor rotr(x, 25)
    }

    private inline fun smallSigma0(x: Int): Int {
        return rotr(x, 7) xor rotr(x, 18) xor (x ushr 3)
    }

    private inline fun smallSigma1(x: Int): Int {
        return rotr(x, 17) xor rotr(x, 19) xor (x ushr 10)
    }
}