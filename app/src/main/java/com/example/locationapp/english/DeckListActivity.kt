package com.example.locationapp.english

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.locationapp.databinding.ActivityDeckListBinding

/** Главный экран: список наборов карточек. По тапу — выбор режима занятий. */
class DeckListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeckListBinding
    private lateinit var progress: Progress

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeckListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progress = Progress(this)
        binding.deckRecycler.layoutManager = LinearLayoutManager(this)

        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.aiBtn.setOnClickListener {
            startActivity(Intent(this, AiAssistantActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Пересоздаём адаптер, чтобы обновить прогресс после занятий.
        binding.deckRecycler.adapter = DeckAdapter(DeckRepository.decks, progress) { deck ->
            showModeDialog(deck)
        }
    }

    private fun showModeDialog(deck: Deck) {
        val options = arrayOf(
            "🃏 Карточки",
            "✅ Тест: слово → перевод",
            "🇬🇧 Тест: рус. → выбор англ.",
            "⌨️ Ввод: рус. → напишите англ.",
            "🔄 Сбросить прогресс"
        )
        AlertDialog.Builder(this)
            .setTitle("${deck.emoji} ${deck.title}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> open(FlashcardActivity::class.java, deck)
                    1 -> openQuiz(deck, ruToEn = false)
                    2 -> openQuiz(deck, ruToEn = true)
                    3 -> open(TypingActivity::class.java, deck)
                    4 -> {
                        progress.resetDeck(deck.id)
                        binding.deckRecycler.adapter?.notifyDataSetChanged()
                    }
                }
            }
            .show()
    }

    private fun open(activity: Class<*>, deck: Deck) {
        startActivity(Intent(this, activity).putExtra(EXTRA_DECK_ID, deck.id))
    }

    private fun openQuiz(deck: Deck, ruToEn: Boolean) {
        startActivity(
            Intent(this, QuizActivity::class.java)
                .putExtra(EXTRA_DECK_ID, deck.id)
                .putExtra(QuizActivity.EXTRA_RU_TO_EN, ruToEn)
        )
    }

    companion object {
        const val EXTRA_DECK_ID = "deck_id"
    }
}
