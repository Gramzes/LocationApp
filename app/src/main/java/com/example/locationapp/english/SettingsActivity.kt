package com.example.locationapp.english

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.databinding.ActivitySettingsBinding

/** Экран настроек ИИ: ввод API-ключа и модели. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AiSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AiSettings(this)

        binding.apiKeyInput.setText(settings.apiKey)
        binding.modelInput.setText(settings.model)

        binding.saveBtn.setOnClickListener {
            settings.apiKey = binding.apiKeyInput.text.toString()
            settings.model = binding.modelInput.text.toString()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
