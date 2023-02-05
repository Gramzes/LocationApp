package com.example.locationapp

import android.Manifest.permission
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.locationapp.databinding.ActivityMainBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.material.snackbar.Snackbar


class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    var requestingLocationUpdates = false

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(permission.ACCESS_FINE_LOCATION, false) -> {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                checkLocationSettings()
            }
            permissions.getOrDefault(permission.ACCESS_COARSE_LOCATION, false) -> {
                showSnackBar("Необходимо разрешение на точное местоположение",
                    "Ок"){}
            } else -> {
                showSnackBar("Невозможно определить местоположение без разрешения",
                    "Ок"){}
            }
        }
    }
    private val getLocationSettingsRequest = registerForActivityResult(ActivityResultContracts
        .StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startLocationUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations){
                    setLocation(location)
                }
            }
        }
        binding.startUpdatesBtn.setOnClickListener {
            when {
                checkPermissions() -> {
                    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                    checkLocationSettings()
                }
                shouldShowRequestPermissionRationale(permission.ACCESS_FINE_LOCATION) -> {
                    showSnackBar("Необходимо разрешение", "Предоставить"){
                        locationPermissionRequest.launch(
                            arrayOf(permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION))
                    }
                }
                else -> {
                    locationPermissionRequest.launch(
                        arrayOf(permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION))
                }

            }
        }
        binding.stopUpdatesBtn.setOnClickListener {
            stopLocationUpdates()
        }
    }
    override fun onResume() {
        super.onResume()
        if (requestingLocationUpdates) startLocationUpdates()
    }
    override fun onPause() {
        super.onPause()
        if (requestingLocationUpdates) stopLocationUpdates()
    }

    fun showSnackBar(text: String, actionText: String, listener: View.OnClickListener){
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
            .setAction(actionText, listener).show()
    }
    fun showUI(){
        when{
            requestingLocationUpdates ->{
                binding.startUpdatesBtn.isEnabled = false
                binding.stopUpdatesBtn.isEnabled = true
            }
            else ->{
                binding.startUpdatesBtn.isEnabled = true
                binding.stopUpdatesBtn.isEnabled = false
            }
        }
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(this,
            permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun checkLocationSettings(){
        locationRequest = createLocationRequest()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)

        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            startLocationUpdates()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException){
                try {
                    getLocationSettingsRequest
                        .launch(IntentSenderRequest.Builder(exception.resolution).build())
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            }
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        requestingLocationUpdates = false
        showUI()
    }
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest,
            locationCallback,
            Looper.getMainLooper())
        requestingLocationUpdates = true
        showUI()
    }

    fun setLocation(location: Location){
        binding.coordTextView.text = "${location.latitude}/${location.longitude}"
    }

    private fun createLocationRequest() : LocationRequest {
        return LocationRequest
            .Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
    }
}