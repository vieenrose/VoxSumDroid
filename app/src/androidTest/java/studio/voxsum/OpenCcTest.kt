package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.text.OpenCcConverter

/** Verify the bundled OpenCC dictionaries actually convert Simplified → Traditional (zh-TW). */
@RunWith(AndroidJUnit4::class)
class OpenCcTest {

    @Test
    fun convertsSimplifiedToTraditional() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cc = OpenCcConverter.get(ctx)
        // From the user's real transcript:
        val out = cc.convert("军方表示，这是第三地织布新竹油料分库厂商在清洗油槽的过程当中。")
        Log.i("OpenCcTest", "converted: $out")
        assertTrue("军→軍", out.contains("軍"))
        assertTrue("这→這", out.contains("這"))
        assertTrue("过程→過程", out.contains("過程"))
        assertTrue("no leftover 军", !out.contains("军"))
        assertEquals("進行", cc.convert("进行"))
        assertEquals("國軍", cc.convert("国军"))
    }
}
