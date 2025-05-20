package com.zucham.qbslibdemo

import java.nio.ByteBuffer
import java.nio.ByteOrder


sealed class GanCubeCommand {
    object RequestFacelets : GanCubeCommand()
    object RequestHardware : GanCubeCommand()
    object RequestBattery  : GanCubeCommand()
    object RequestReset    : GanCubeCommand()
}


sealed class GanCubeEvent {
    data class Gyro(
        val timestamp: Long,
        val quaternion: Quaternion,
        val velocity: Velocity
    ) : GanCubeEvent()

    data class Move(
        val serial: Int,
        val timestamp: Long,
        val cubeTimestamp: Long,
        val face: Int,
        val direction: Int
    ) : GanCubeEvent()

    data class Facelets(
        val serial: Int,
        val timestamp: Long,
        val cp: List<Int>,
        val co: List<Int>,
        val ep: List<Int>,
        val eo: List<Int>
    ) : GanCubeEvent()

    data class Hardware(
        val timestamp: Long,
        val name: String,
        val hwVersion: String,
        val swVersion: String,
        val gyroSupported: Boolean
    ) : GanCubeEvent()

    data class Battery(
        val timestamp: Long,
        val batteryLevel: Int
    ) : GanCubeEvent()

    object Disconnect : GanCubeEvent()
}

data class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double)
data class Velocity(val x: Double, val y: Double, val z: Double)


class GanProtocolMessageView(message: ByteArray) {
    private val bits: String = message
        .joinToString("") { b ->
            (b.toInt() and 0xFF)
                .toString(2)
                .padStart(8, '0')
        }

    /**
     * Extract an unsigned bit‐word of [bitLength] starting at [startBit].
     * If [littleEndian] is true and length is 16/32, the result is in LE.
     */
    fun getBitWord(
        startBit: Int,
        bitLength: Int,
        littleEndian: Boolean = false
    ): Long {
        require(bitLength in 1..32) { "bitLength must be 1...32, got $bitLength" }
        val slice = bits.substring(startBit, startBit + bitLength)
        return when {
            bitLength <= 8 ->                    // Any 1..8 bit field
                slice.toInt(2).toLong()

            bitLength == 16 || bitLength == 32 -> {
                // break into bytes
                val byteCount = bitLength / 8
                val buf = ByteArray(byteCount) { i ->
                    val off = startBit + i * 8
                    bits.substring(off, off + 8).toInt(2).toByte()
                }
                val bb = ByteBuffer.wrap(buf)
                if (littleEndian) bb.order(ByteOrder.LITTLE_ENDIAN)
                if (bitLength == 16)
                    bb.short.toInt().and(0xFFFF).toLong()
                else
                    bb.int.toLong().and(0xFFFFFFFFL)
            }

            else -> throw IllegalArgumentException(
                "Unsupported bitLength $bitLength"
            )
        }
    }
}


class GanGen2ProtocolDriver {
    private var lastSerial: Int = -1
    private var lastMoveTimestamp: Long = 0
    private var cubeTimestamp: Long = 0

    /** Build a 20‐byte command or return null if unsupported */
    fun createCommandMessage(cmd: GanCubeCommand): ByteArray? {
        return when (cmd) {
            GanCubeCommand.RequestFacelets -> ByteArray(20).apply { this[0] = 0x04 }
            GanCubeCommand.RequestHardware -> ByteArray(20).apply { this[0] = 0x05 }
            GanCubeCommand.RequestBattery  -> ByteArray(20).apply { this[0] = 0x09 }
            GanCubeCommand.RequestReset    -> byteArrayOf(
                0x0A,0x05,0x39,0x77,0x00,0x00,0x01,0x23,
                0x45,0x67, 0x89.toByte(), 0xAB.toByte(),0x00,0x00,0x00,0x00,
                0x00,0x00,0x00,0x00
            )
        }
    }

    /**
     * Parse a decrypted 20‐byte message into zero or more events.
     */
    fun handleStateEvent(eventMessage: ByteArray): List<GanCubeEvent> {
        val timestamp = System.currentTimeMillis()
        val msg = GanProtocolMessageView(eventMessage)
        val evType = msg.getBitWord(0, 4).toInt()

        return when (evType) {
            0x01 -> parseGyro(msg, timestamp)
            0x02 -> parseMove(msg, timestamp)
            0x04 -> parseFacelets(msg, timestamp)
            0x05 -> parseHardware(msg, timestamp)
            0x09 -> listOf(parseBattery(msg, timestamp))
            0x0D -> listOf(GanCubeEvent.Disconnect)
            else -> emptyList()
        }
    }

    private fun parseGyro(v: GanProtocolMessageView, ts: Long): List<GanCubeEvent> {
        val qw = v.getBitWord(4, 16).toInt()
        val qx = v.getBitWord(20, 16).toInt()
        val qy = v.getBitWord(36, 16).toInt()
        val qz = v.getBitWord(52, 16).toInt()
        val vx = v.getBitWord(68, 4).toInt()
        val vy = v.getBitWord(72, 4).toInt()
        val vz = v.getBitWord(76, 4).toInt()

        fun fixSigned(value: Int, bits: Int) =
            (1 - (value shr (bits - 1)) * 2) * (value and ((1 shl (bits - 1)) - 1)).toDouble() /
                    ((1 shl (bits - 1)) - 1)

        val quaternion = Quaternion(
            x = fixSigned(qx, 16),
            y = fixSigned(qy, 16),
            z = fixSigned(qz, 16),
            w = fixSigned(qw, 16)
        )
        val velocity = Velocity(
            x = fixSigned(vx, 4),
            y = fixSigned(vy, 4),
            z = fixSigned(vz, 4)
        )
        return listOf(GanCubeEvent.Gyro(ts, quaternion, velocity))
    }

    private fun parseMove(v: GanProtocolMessageView, ts: Long): List<GanCubeEvent> {
        val serial = v.getBitWord(4, 8).toInt()
        val diff = if (lastSerial == -1) 0 else minOf((serial - lastSerial) and 0xFF, 7)
        lastSerial = serial

        val events = mutableListOf<GanCubeEvent>()
        if (diff > 0) {
            for (i in diff - 1 downTo 0) {
                val face = v.getBitWord(12 + 5 * i, 4).toInt()
                val dir  = v.getBitWord(16 + 5 * i, 1).toInt()
                val elapsed = v.getBitWord(47 + 16 * i, 16).let { e ->
                    if (e == 0L) ts - lastMoveTimestamp else e
                }
                cubeTimestamp += elapsed
                events += GanCubeEvent.Move(
                    serial = (serial - i) and 0xFF,
                    timestamp = ts,
                    cubeTimestamp = cubeTimestamp,
                    face = face,
                    direction = dir
                )
            }
            lastMoveTimestamp = ts
        }
        return events
    }

    private fun parseFacelets(v: GanProtocolMessageView, ts: Long): List<GanCubeEvent> {
        val serial = v.getBitWord(4, 8).toInt()
        if (lastSerial == -1) lastSerial = serial

        val cp = mutableListOf<Int>()
        val co = mutableListOf<Int>()
        repeat(7) { i ->
            cp += v.getBitWord(12 + i * 3, 3).toInt()
            co += v.getBitWord(33 + i * 2, 2).toInt()
        }
        cp += 28 - cp.sum()
        co += (3 - co.sum() % 3) % 3

        val ep = mutableListOf<Int>()
        val eo = mutableListOf<Int>()
        repeat(11) { i ->
            ep += v.getBitWord(47 + i * 4, 4).toInt()
            eo += v.getBitWord(91 + i, 1).toInt()
        }
        ep += 66 - ep.sum()
        eo += (2 - eo.sum() % 2) % 2

        return listOf(GanCubeEvent.Facelets(serial, ts, cp, co, ep, eo))
    }

    private fun parseHardware(v: GanProtocolMessageView, ts: Long): List<GanCubeEvent> {
        val hwMajor = v.getBitWord(8, 8).toInt()
        val hwMinor = v.getBitWord(16, 8).toInt()
        val swMajor = v.getBitWord(24, 8).toInt()
        val swMinor = v.getBitWord(32, 8).toInt()
        val gyroSup = v.getBitWord(104, 1) != 0L

        val nameBuilder = StringBuilder()
        repeat(8) { i ->
            val c = v.getBitWord(40 + i * 8, 8).toInt()
            nameBuilder.append(c.toChar())
        }

        return listOf(
            GanCubeEvent.Hardware(
                timestamp = ts,
                name = nameBuilder.toString(),
                hwVersion = "$hwMajor.$hwMinor",
                swVersion = "$swMajor.$swMinor",
                gyroSupported = gyroSup
            )
        )
    }

    private fun parseBattery(v: GanProtocolMessageView, ts: Long): GanCubeEvent.Battery {
        val level = v.getBitWord(8, 8).toInt().coerceAtMost(100)
        return GanCubeEvent.Battery(timestamp = ts, batteryLevel = level)
    }
}
