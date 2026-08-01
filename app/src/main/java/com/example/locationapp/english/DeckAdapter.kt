package com.example.locationapp.english

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.locationapp.databinding.ItemDeckBinding

/** Адаптер списка наборов карточек. */
class DeckAdapter(
    private val decks: List<Deck>,
    private val progress: Progress,
    private val onClick: (Deck) -> Unit
) : RecyclerView.Adapter<DeckAdapter.DeckViewHolder>() {

    inner class DeckViewHolder(val binding: ItemDeckBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeckViewHolder {
        val binding = ItemDeckBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeckViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeckViewHolder, position: Int) {
        val deck = decks[position]
        val learned = progress.learnedCount(deck.id)
        val total = deck.cards.size
        holder.binding.deckEmoji.text = deck.emoji
        holder.binding.deckTitle.text = deck.title
        holder.binding.deckProgress.text = "$learned / $total выучено"
        holder.binding.deckProgressBar.progress =
            if (total == 0) 0 else (learned * 100 / total)
        holder.binding.root.setOnClickListener { onClick(deck) }
    }

    override fun getItemCount(): Int = decks.size
}
