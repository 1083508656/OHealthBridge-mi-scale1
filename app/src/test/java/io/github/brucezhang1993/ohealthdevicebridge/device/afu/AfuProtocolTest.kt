package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import org.junit.Assert.*
import org.junit.Test

class AfuProtocolTest{
    @Test fun parsesLiveWeightSample(){val p=AfuProtocol.parse(hex("AF45B2ED080002800188384B0000000000005106")) as AfuProtocol.LiveWeight;assertEquals(60850,p.weightGrams);assertEquals(2,p.mode);assertFalse(p.locked)}
    @Test fun parsesStoredRecordSample(){val p=AfuProtocol.parse(hex("AF450046A4826A7AEE0880000127020000005404")) as AfuProtocol.StoredRecord;assertEquals(0,p.historyType);assertEquals(0,p.sequence);assertEquals(61050,p.weightGrams);assertEquals(551,p.resistanceOhm);assertTrue(p.locked)}
    @Test fun parsesImpedanceSample(){val p=AfuProtocol.parse(hex("AF4501002702000000000000000000000000521C")) as AfuProtocol.Impedance;assertTrue(p.valid);assertEquals(551,p.resistanceOhm)}
    @Test fun buildsKnownTimeSyncSample(){val f=AfuProtocol.buildTimeSync(1786810554L);assertEquals("AF4516BA90806A20000000000000000000005F09",AfuProtocol.toHex(f));assertTrue(AfuProtocol.isValid(f))}
    @Test fun buildsHistoryAckWithSequenceCorrelation(){val f=AfuProtocol.buildAck(AfuProtocol.WIRE_STORED,5);assertEquals("AF45125400050000000000000000000000005F0A",AfuProtocol.toHex(f));assertTrue(AfuProtocol.isValid(f))}
    @Test fun buildsProfileWithDocumentedFields(){val f=AfuProtocol.buildProfile(AfuProtocol.ProfileVariant.INITIAL,1786810580L,1,29,true,50.0);assertEquals("AF453FD490806A0001AFB6171D01881303006107",AfuProtocol.toHex(f))}
    private fun hex(v:String)=v.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
}
