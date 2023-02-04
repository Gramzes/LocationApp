package com.example.locationapp

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.locationapp.databinding.ActivityMainBinding
import android.Manifest.permission
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding

    val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(permission.ACCESS_FINE_LOCATION, false) -> {
                // Precise location access granted.
            }
            permissions.getOrDefault(permission.ACCESS_COARSE_LOCATION, false) -> {
                // Only approximate location access granted.
            } else -> {
            // No location access granted.
        }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startUpdatesBtn.setOnClickListener {
            when{
                ContextCompat.checkSelfPermission(this,
                    permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {

                    }
                shouldShowRequestPermissionRationale(permission.ACCESS_FINE_LOCATION) -> {
                    showInContextUI()
                }
                else -> {
                    locationPermissionRequest.launch(
                        arrayOf(permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION))
                }
            }
        }
    }

    private fun showInContextUI() {
        Snackbar.make(binding.root,
            "Необходимо разрешение", Snackbar.LENGTH_LONG).setAction("Ok"){
            locationPermissionRequest.launch(
                arrayOf(permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION))
        }.show()
    }
}