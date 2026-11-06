package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import kotlin.math.roundToInt

object AfuProtocol {
    const val FRAME_SIZE = 20

    const val WIRE_LIVE_WEIGHT = 0x51
    const val WIRE_IMPEDANCE = 0x52
    const val WIRE_STORED = 0x54
    const val WIRE_CONTROL = 0x5F
    const val WIRE_PROFILE = 0x61
    const val WIRE_SYNC = 0x62

    enum class ProfileVariant(val code: Int, val openByte: Int) {
        INITIAL(0x3F, 0xB6), GET_WEIGH_AGAIN_1(0x4B, 0xB6), GET_WEIGH_AGAIN_2(0x5B, 0xD9), FINAL(0x68, 0xD9), HISTORY_DUMP(0x8E, 0xDE)
    }

    sealed interface ParsedFrame { val raw: ByteArray }
    data class LiveWeight(val weightGrams:Int,val locked:Boolean,val mode:Int,override val raw:ByteArray):ParsedFrame
    data class StoredRecord(val historyType:Int,val sequence:Int,val timestampEpochSeconds:Long,val weightGrams:Int,val resistanceOhm:Int,val locked:Boolean,override val raw:ByteArray):ParsedFrame
    data class Impedance(val valid:Boolean,val resistanceOhm:Int,override val raw:ByteArray):ParsedFrame
    data class ReportChunk(val index:Int,val payload:ByteArray,override val raw:ByteArray):ParsedFrame
    data class ControlAck(val replyCommand:Int,val replyOperation:Int,override val raw:ByteArray):ParsedFrame
    data class UnknownFrame(val wireType:Int,override val raw:ByteArray):ParsedFrame

    fun isValid(frame:ByteArray):Boolean {
        if(frame.size!=FRAME_SIZE||u8(frame[0])!=0xAF||u8(frame[1])!=0x45)return false
        var sum=0; for(i in 2..18) sum+=u8(frame[i])
        return (sum and 0x1F)==(u8(frame[19]) and 0x1F)
    }

    fun parse(frame:ByteArray):ParsedFrame? {
        if(!isValid(frame))return null
        val raw=frame.copyOf()
        return when(val wireType=u8(raw[18])) {
            WIRE_LIVE_WEIGHT -> { val flags=le32u(raw,2); LiveWeight((flags and 0x3FFFF).toInt(),(flags and 0x80000000L)!=0L,u8(raw[6]),raw) }
            WIRE_STORED -> { val meta=u8(raw[2]); val flags=le32u(raw,7); StoredRecord(meta and 0x03,(meta ushr 2) and 0x3F,le32u(raw,3),(flags and 0x3FFFF).toInt(),le16(raw,13),(flags and 0x80000000L)!=0L,raw) }
            WIRE_IMPEDANCE -> Impedance(u8(raw[2])==0x01,le16(raw,4),raw)
            WIRE_CONTROL -> when(u8(raw[2])) { 0x14->ReportChunk(u8(raw[3]),raw.copyOfRange(4,18),raw); 0x12->ControlAck(u8(raw[3]),u8(raw[4]),raw); else->UnknownFrame(wireType,raw) }
            else -> UnknownFrame(wireType,raw)
        }
    }

    fun buildTimeSync(epochSeconds:Long)=frame(WIRE_CONTROL){ this[2]=0x16; putLe32(this,3,epochSeconds); this[7]=0x20 }
    fun buildSyncFirst()=byteArrayOf(0xAF.b,0x45.b,0x04,0x00,0x01,0xAF.b,0xB6.b,0x17,0x1D,0x01,0x00,0x02,0xB4.b,0xC6.b,0x1B,0x56,0x01,0x00,0x62,0x00).withChecksum()
    fun buildSyncSecond()=byteArrayOf(0xAF.b,0x45.b,0x04,0x01,0x03,0xA5.b,0xF8.b,0x11,0x1D,0x02,0x00,0x04,0xB4.b,0xC6.b,0x1B,0x1D,0x01,0x00,0x62,0x00).withChecksum()
    fun buildProfile(variant:ProfileVariant,epochSeconds:Long,userSlot:Int,age:Int,male:Boolean,targetKg:Double)=frame(WIRE_PROFILE){ this[2]=variant.code.b; putLe32(this,3,epochSeconds); this[7]=0; this[8]=userSlot.coerceIn(0,255).b; this[9]=0xAF.b; this[10]=variant.openByte.b; this[11]=0x17; this[12]=age.coerceIn(0,255).b; this[13]=if(male)1 else 0; putLe16(this,14,(targetKg*100.0).roundToInt().coerceIn(0,0xFFFF)); this[16]=0x03; this[17]=0 }
    fun buildHistoryDump(epochSeconds:Long,userSlot:Int,age:Int,male:Boolean,targetKg:Double)=buildProfile(ProfileVariant.HISTORY_DUMP,epochSeconds,userSlot,age,male,targetKg)
    fun buildAck(replyType:Int,correlation:Int=0)=frame(WIRE_CONTROL){ this[2]=0x12; this[3]=replyType.b; this[4]=0; this[5]=correlation.coerceIn(0,255).b }
    fun buildChunkRequest()=frame(WIRE_CONTROL){this[2]=0x14}
    fun toHex(bytes:ByteArray)=bytes.joinToString(""){"%02X".format(u8(it))}

    private fun frame(wireType:Int,body:ByteArray.()->Unit):ByteArray { val f=ByteArray(FRAME_SIZE); f[0]=0xAF.b; f[1]=0x45; f[18]=wireType.b; f.body(); return f.withChecksum() }
    private fun ByteArray.withChecksum():ByteArray { var sum=0; for(i in 2..18)sum+=u8(this[i]); this[19]=(sum and 0x1F).b; return this }
    private fun le16(raw:ByteArray,o:Int)=u8(raw[o]) or (u8(raw[o+1]) shl 8)
    private fun le32u(raw:ByteArray,o:Int)=u8(raw[o]).toLong() or (u8(raw[o+1]).toLong() shl 8) or (u8(raw[o+2]).toLong() shl 16) or (u8(raw[o+3]).toLong() shl 24)
    private fun putLe16(raw:ByteArray,o:Int,v:Int){raw[o]=(v and 0xFF).b;raw[o+1]=((v ushr 8) and 0xFF).b}
    private fun putLe32(raw:ByteArray,o:Int,v:Long){raw[o]=(v and 0xFF).toInt().b;raw[o+1]=((v ushr 8) and 0xFF).toInt().b;raw[o+2]=((v ushr 16) and 0xFF).toInt().b;raw[o+3]=((v ushr 24) and 0xFF).toInt().b}
    private fun u8(v:Byte)=v.toInt() and 0xFF
    private val Int.b:Byte get()=toByte()
}
