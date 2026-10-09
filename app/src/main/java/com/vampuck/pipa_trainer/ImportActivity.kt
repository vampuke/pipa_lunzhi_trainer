package com.vampuck.pipa_trainer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vampuck.pipa_trainer.data.ImportStore
import com.vampuck.pipa_trainer.data.JScoreJson
import com.vampuck.pipa_trainer.data.PracticePieces
import com.vampuck.pipa_trainer.databinding.ActivityImportBinding

/**
 * 导入自定义简谱：粘贴 JSON -> 解析 -> 保存并注册 -> 可在跟练列表看到。
 *
 * 版权说明：仅供用户导入自己拥有或自行录入的谱子作个人练习之用。
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var b: ActivityImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImportBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 长格式说明放输入框的 placeholder（输入后自动消失），label 位置保持短标题。
        b.jsonLabel.hint = getString(R.string.import_json_label)
        b.jsonLabel.placeholderText = JScoreJson.TEMPLATE_HINT

        b.btnImport.setOnClickListener {
            val text = b.inputJson.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                toast(getString(R.string.import_empty)); return@setOnClickListener
            }
            try {
                val ip = JScoreJson.parse(text)
                PracticePieces.registerImported(ip)
                ImportStore.save(this, ip)
                toast(getString(R.string.import_ok, ip.piece.title))
                finish()
            } catch (e: Throwable) {
                toast(getString(R.string.import_fail, e.message ?: "格式错误"))
            }
        }

        b.btnFillTemplate.setOnClickListener {
            b.inputJson.setText(JScoreJson.TEMPLATE_EXAMPLE)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
