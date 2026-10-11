package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.update.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑：自更新的 URL 白名单与 md5 格式校验（无 Android 依赖）。 */
class UpdateCheckerTest {

    @Test
    fun `only https on the allowed host passes`() {
        assertTrue(UpdateChecker.isAllowedUrl("https://ora.vampuck.com/dl/pipa-trainer.apk"))
    }

    @Test
    fun `http is rejected even on the allowed host`() {
        assertFalse(UpdateChecker.isAllowedUrl("http://ora.vampuck.com/dl/pipa-trainer.apk"))
    }

    @Test
    fun `other hosts are rejected`() {
        assertFalse(UpdateChecker.isAllowedUrl("https://evil.example.com/x.apk"))
        assertFalse(UpdateChecker.isAllowedUrl("https://ora.vampuck.com.evil.com/x.apk"))
    }

    @Test
    fun `garbage url is rejected`() {
        assertFalse(UpdateChecker.isAllowedUrl("not a url"))
        assertFalse(UpdateChecker.isAllowedUrl(""))
    }

    @Test
    fun `md5 must be 32 hex chars`() {
        assertTrue(UpdateChecker.isValidMd5("509fda67e3ecac70497fea047ea6aedd"))
        assertTrue(UpdateChecker.isValidMd5("AEEC6CE046F67A6511BDAF6DD9C33A5E"))
        assertFalse(UpdateChecker.isValidMd5(""))
        assertFalse(UpdateChecker.isValidMd5("abc"))
        assertFalse(UpdateChecker.isValidMd5("509fda67e3ecac70497fea047ea6aedd00"))  // 太长
        assertFalse(UpdateChecker.isValidMd5("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))      // 非十六进制
    }
}
