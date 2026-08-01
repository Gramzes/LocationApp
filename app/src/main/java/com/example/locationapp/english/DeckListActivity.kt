package com.example.locationapp.english

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.locationapp.databinding.ActivityDeckListBinding

/** Экран со списком наборов карточек. По тапу предлагает выбрать режим: карточки или тест. */
class DeckListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeckListBinding
    private lateinit var progress: Progress

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeckListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progress = Progress(this)
        binding.deckRecycler.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        // Пересоздаём адаптер, чтобы обновить прогресс после занятий.
        binding.deckRecycler.adapter = DeckAdapter(DeckRepository.decks, progress) { deck ->
            showModeDialog(deck)
        }
    }

    private fun showModeDialog(deck: Deck) {
        val options = arrayOf("🃏 Карточки", "✅ Тест (выбор варианта)", "🔄 Сбросить прогресс")
        AlertDialog.Builder(this)
            .setTitle("${deck.emoji} ${deck.title}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, FlashcardActivity::class.java)
                            .putExtra(EXTRA_DECK_ID, deck.id)
                    )
                    1 -> startActivity(
                        Intent(this, QuizActivity::class.java)
                            .putExtra(EXTRA_DECK_ID, deck.id)
                    )
                    2 -> {
                        progress.resetDeck(deck.id)
                        binding.deckRecycler.adapter?.notifyDataSetChanged()
                    }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_DECK_ID = "deck_id"
    }
}
