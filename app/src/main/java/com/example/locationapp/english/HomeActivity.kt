package com.example.locationapp.english

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.MainActivity
import com.example.locationapp.databinding.ActivityHomeBinding
import com.example.locationapp.game.GameActivity

/** Стартовый экран: выбор между игрой, карточками английского и определением местоположения. */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.gameBtn.setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
        binding.englishBtn.setOnClickListener {
            startActivity(Intent(this, DeckListActivity::class.java))
        }
        binding.locationBtn.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}
