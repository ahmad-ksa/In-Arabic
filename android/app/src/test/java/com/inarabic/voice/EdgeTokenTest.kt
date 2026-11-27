package com.inarabic.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** the same tokens as edge-tts 7.3.1 (generate_sec_ms_gec_v2) gives for these times */
class EdgeTokenTest
{
	@Test fun sameAsEdgeTts()
	{
		assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6", EdgeToken.token(0))
		assertEquals("C29E7DDEF5FB649D-61E9F6EB1AD5822A084EB60A9E42D9E126A0C64CD927BEB33792E769C9A284CE", EdgeToken.token(1764252000))
		assertEquals("6521DCC55F99AAD7-6D04852628E3DF4D0EF33213239BDC3C48750FE37FE5E4591F28C209CE0A3FA2", EdgeToken.token(1795000000))
		assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654", EdgeToken.token(1800000123))
	}

	@Test fun sameWithinFiveMinutes() = assertEquals(EdgeToken.token(1795000000), EdgeToken.token(1795000000 + 199))
}
