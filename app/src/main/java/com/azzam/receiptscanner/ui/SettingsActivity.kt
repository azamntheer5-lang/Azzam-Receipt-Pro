package com.azzam.receiptscanner.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.azzam.receiptscanner.R
import com.azzam.receiptscanner.databinding.ActivitySettingsBinding
import com.azzam.receiptscanner.databinding.CardApiKeyBinding
import com.azzam.receiptscanner.llm.LlmManager
import com.azzam.receiptscanner.storage.ApiKeyStore
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * شاشة الإعدادات: إدارة مفاتيح API لكل المحركات + اختيار المحرك النشط.
 *
 * التطوير (المرحلة 2): استبدال نافذة حوار مفتاح Claude الواحد بشاشة كاملة
 * تدعم Multi-LLM. كل مفتاح يُخزَّن مشفّراً عبر ApiKeyStore (الذي يوسّع
 * SecureStorage). المحرك النشط يُخزَّن في SharedPreferences.
 *
 * ملاحظة ViewBinding: البطاقات الأربع تستخدم <include> بـ id. نوع الحقل
 * المولّد قد يكون الجذر (MaterialCardView) وليس CardApiKeyBinding حسب
 * إصدار AGP. لذا نستخدم findViewById + CardApiKeyBinding.bind لضمان
 * الوصول الآمن للحقول الداخلية عبر كل إصدارات ViewBinding.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private data class EngineCard(
        val engineId: String,
        val displayName: String,
        val description: String,
        val editKey: EditText,
        val clearButton: MaterialButton,
        val nameView: android.widget.TextView,
        val descView: android.widget.TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        setupEngineSpinner()
        setupEngineCards()
    }

    private fun setupEngineSpinner() {
        val engines = LlmManager.allEngines
        val displayNames = engines.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayNames)
        binding.spinnerEngine.setAdapter(adapter)

        val activeId = ApiKeyStore.getActiveEngine(this)
        val activeEngine = engines.firstOrNull { it.engineId == activeId }
        binding.spinnerEngine.setText(activeEngine?.displayName ?: displayNames.first(), false)

        binding.spinnerEngine.setOnItemClickListener { _, _, position, _ ->
            val selected = engines[position]
            ApiKeyStore.setActiveEngine(this, selected.engineId)
            toast(getString(R.string.settings_engine_set, selected.displayName))
        }
    }

    private fun bindCard(rootId: Int): CardApiKeyBinding {
        val root = findViewById<View>(rootId)
        return CardApiKeyBinding.bind(root)
    }

    private fun setupEngineCards() {
        val claude = bindCard(R.id.cardClaude)
        val gemini = bindCard(R.id.cardGemini)
        val groq = bindCard(R.id.cardGroq)
        val hf = bindCard(R.id.cardHuggingFace)

        val cards = listOf(
            EngineCard(
                engineId = ApiKeyStore.ENGINE_CLAUDE,
                displayName = getString(R.string.engine_claude_name),
                description = getString(R.string.engine_claude_desc),
                editKey = claude.editApiKey,
                clearButton = claude.buttonClearKey,
                nameView = claude.textEngineName,
                descView = claude.textEngineDesc
            ),
            EngineCard(
                engineId = ApiKeyStore.ENGINE_GEMINI,
                displayName = getString(R.string.engine_gemini_name),
                description = getString(R.string.engine_gemini_desc),
                editKey = gemini.editApiKey,
                clearButton = gemini.buttonClearKey,
                nameView = gemini.textEngineName,
                descView = gemini.textEngineDesc
            ),
            EngineCard(
                engineId = ApiKeyStore.ENGINE_GROQ,
                displayName = getString(R.string.engine_groq_name),
                description = getString(R.string.engine_groq_desc),
                editKey = groq.editApiKey,
                clearButton = groq.buttonClearKey,
                nameView = groq.textEngineName,
                descView = groq.textEngineDesc
            ),
            EngineCard(
                engineId = ApiKeyStore.ENGINE_HUGGINGFACE,
                displayName = getString(R.string.engine_hf_name),
                description = getString(R.string.engine_hf_desc),
                editKey = hf.editApiKey,
                clearButton = hf.buttonClearKey,
                nameView = hf.textEngineName,
                descView = hf.textEngineDesc
            )
        )

        for (card in cards) {
            card.nameView.text = card.displayName
            card.descView.text = card.description

            // اعرض المفتاح الحالي مموّهاً (إن وُجد)
            val currentKey = ApiKeyStore.getApiKey(this, card.engineId)
            if (!currentKey.isNullOrBlank()) {
                card.editKey.setText(maskKey(currentKey))
            }

            // حفظ عند فقدان التركيز
            card.editKey.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) saveKeyForCard(card)
            }

            // زر الحذف
            card.clearButton.setOnClickListener {
                ApiKeyStore.clearApiKey(this, card.engineId)
                card.editKey.setText("")
                toast(getString(R.string.settings_key_cleared, card.displayName))
            }
        }
    }

    private fun saveKeyForCard(card: EngineCard) {
        val raw = card.editKey.text.toString().trim()
        val existing = ApiKeyStore.getApiKey(this, card.engineId)

        // إن كان الحقل يعرض نسخة مموّهة (نقاط فقط) ولم يُعدلها المستخدم، تجاهل
        if (raw.all { it == '•' || it == '*' }) return

        if (raw.isBlank()) {
            if (existing != null) ApiKeyStore.clearApiKey(this, card.engineId)
            return
        }
        if (raw == existing) return

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ApiKeyStore.setApiKey(this@SettingsActivity, card.engineId, raw)
            }
            toast(getString(R.string.settings_key_saved, card.displayName))
        }
    }

    private fun maskKey(key: String): String =
        if (key.length <= 4) "••••"
        else "•".repeat(key.length - 4) + key.takeLast(4)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
