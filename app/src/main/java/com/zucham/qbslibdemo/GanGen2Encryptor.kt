package com.zucham.qbslibdemo

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


val ganGen2Key = byteArrayOf(
    0x01.toByte(), 0x02.toByte(), 0x42.toByte(), 0x28.toByte(),
    0x31.toByte(), 0x91.toByte(), 0x16.toByte(), 0x07.toByte(),
    0x20.toByte(), 0x05.toByte(), 0x18.toByte(), 0x54.toByte(),
    0x42.toByte(), 0x11.toByte(), 0x12.toByte(), 0x53.toByte()
)
val ganGen2Iv = byteArrayOf(
    0x11.toByte(), 0x03.toByte(), 0x32.toByte(), 0x28.toByte(),
    0x21.toByte(), 0x01.toByte(), 0x76.toByte(), 0x27.toByte(),
    0x20.toByte(), 0x95.toByte(), 0x78.toByte(), 0x14.toByte(),
    0x32.toByte(), 0x12.toByte(), 0x02.toByte(), 0x43.toByte()
)

class GanGen2Encryptor(
    key: ByteArray = ganGen2Key,
    iv: ByteArray = ganGen2Iv,
    salt: ByteArray
) {
    private val TAG = "GanGen2Encryptor"
    private val _key: ByteArray
    private val _iv: ByteArray

    init {
        require(key.size == 16) { "Key must be 16 bytes (128-bit) long" }
        require(iv.size == 16) { "Iv must be 16 bytes (128-bit) long" }
        require(salt.size == 6) { "Salt must be 6 bytes (48-bit) long" }

        // apply salt to first 6 bytes of key and iv
        _key = key.copyOf()
        _iv = iv.copyOf()
        for (i in 0 until 6) {
            val k = (_key[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)
            val v = (_iv[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)
            _key[i] = (k % 0xFF).toByte()
            _iv[i] = (v % 0xFF).toByte()
        }
    }

    /** Encrypts a single 16-byte block in place at [offset] */
    private fun encryptChunk(buffer: ByteArray, offset: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(_key, "AES"),
            IvParameterSpec(_iv)
        )
        val chunk = cipher.doFinal(buffer, offset, 16)
        System.arraycopy(chunk, 0, buffer, offset, 16)
    }

    /** Decrypts a single 16-byte block in place at [offset] */
    private fun decryptChunk(buffer: ByteArray, offset: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(_key, "AES"),
            IvParameterSpec(_iv)
        )
        val chunk = cipher.doFinal(buffer, offset, 16)
        System.arraycopy(chunk, 0, buffer, offset, 16)
    }

    /** Encrypt first and last 16-byte blocks */
    fun encrypt(data: ByteArray): ByteArray {
        require(data.size >= 16) { "Data must be at least 16 bytes long" }
        Log.d(TAG, "Encrypting (hex): ${bytesToHex(data)}")
        val res = data.copyOf()
        encryptChunk(res, 0)
        if (res.size > 16) encryptChunk(res, res.size - 16)
        Log.d(TAG, "Encrypted (hex):  ${bytesToHex(res)}")
        return res
    }

    /** Decrypt last then first 16-byte blocks, and log detailed byte view */
    fun decrypt(data: ByteArray): ByteArray {
        require(data.size >= 16) { "Data must be at least 16 bytes long" }
        Log.d(TAG, "Decrypting (hex): ${bytesToHex(data)}")
        val res = data.copyOf()
        if (res.size > 16) decryptChunk(res, res.size - 16)
        decryptChunk(res, 0)
        Log.d(TAG, "Decrypted (hex):  ${bytesToHex(res)}")
        Log.d(TAG, "Decrypted bytes:\n${formatBytesDetailed(res)}")
        return res
    }

    /** helper to format each byte as “Byte i: hh = bbbbbbbb” */
    private fun formatBytesDetailed(bytes: ByteArray): String {
        return bytes.mapIndexed { i, b ->
            val hex = String.format("%02x", b)
            val bin = String.format(
                "%8s",
                Integer.toBinaryString(b.toInt() and 0xFF)
            ).replace(' ', '0')
            "Byte $i:  $hex = $bin"
        }.joinToString("\n")
    }
}
