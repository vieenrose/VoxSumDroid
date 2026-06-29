package studio.voxsum

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.text.ChineseScript
import studio.voxsum.core.text.OpenCcConverter

/**
 * Validates the bundled OpenCC `s2twp` pipeline end-to-end against the real assets on device:
 * stage 1 (Simplified→Traditional) AND the new stage-2 Taiwan PHRASE pass — the "p" in s2twp.
 * Without TWPhrases these convert character-by-character to mainland-leaning Traditional
 * (信息 stays 信息, 視頻 not 影片, 軟件 not 軟體); with it they read native Taiwanese.
 */
@RunWith(AndroidJUnit4::class)
class OpenCcConverterTest {

    private val cc by lazy { OpenCcConverter.get(InstrumentationRegistry.getInstrumentation().targetContext, ChineseScript.TRADITIONAL) }

    @Test fun taiwanPhrasesNotJustCharacters() {
        assertEquals("資訊", cc.convert("信息"))   // 信息 (identical script) → Taiwan phrase
        assertEquals("資料", cc.convert("数据"))   // 数据 → 數據 → 資料
        assertEquals("影片", cc.convert("视频"))   // 视频 → 視頻 → 影片
        assertEquals("軟體", cc.convert("软件"))   // 软件 → 軟件 → 軟體
        assertEquals("檔案", cc.convert("文件"))   // 文件 → 檔案
        assertEquals("預設", cc.convert("默认"))   // 默认 → 默認 → 預設
    }

    @Test fun phrasesConvertInsideASentence() {
        assertEquals("這個軟體的資訊很完整", cc.convert("这个软件的信息很完整"))
    }

    @Test fun stageOneStillConvertsPlainSimplified() {
        assertEquals("電腦", cc.convert("电脑"))   // STPhrases s2t, no Taiwan-specific swap
    }

    @Test fun nonChineseIsUntouched() {
        assertEquals("Hello, world! 123", cc.convert("Hello, world! 123"))
    }
}
