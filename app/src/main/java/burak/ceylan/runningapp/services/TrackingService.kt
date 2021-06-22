package burak.ceylan.runningapp.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent.readPendingIntentOrNullFromParcel
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import burak.ceylan.runningapp.R
import burak.ceylan.runningapp.other.Constants.ACTION_PAUSE_SERVICE
import burak.ceylan.runningapp.other.Constants.ACTION_SHOW_TRACKING_FRAGMENT
import burak.ceylan.runningapp.other.Constants.ACTION_START_OR_RESUME_SERVICE
import burak.ceylan.runningapp.other.Constants.ACTION_STOP_SERVICE
import burak.ceylan.runningapp.other.Constants.FASTEST_LOCATION_INTERVAL
import burak.ceylan.runningapp.other.Constants.LOCATION_UPDATE_INTERVAL
import burak.ceylan.runningapp.other.Constants.NOTIFICATION_CHANNEL_ID
import burak.ceylan.runningapp.other.Constants.NOTIFICATION_CHANNEL_NAME
import burak.ceylan.runningapp.other.Constants.NOTIFICATION_ID
import burak.ceylan.runningapp.other.Constants.TIMER_UPDATE_INTERVAL
import burak.ceylan.runningapp.other.TrackingUtility
import burak.ceylan.runningapp.ui.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

typealias Polyline = MutableList<LatLng>
typealias Polylines = MutableList<Polyline>

@AndroidEntryPoint
class TrackingService : LifecycleService() {

    var isFirstRun = true
    var serviceKilled = false
    @Inject
    lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private val timeRunInSeconds = MutableLiveData<Long>()
    //companion object içinde değil çünkü LiveData'ya sadece bu servis içinde erişilecek

    @Inject
    lateinit var baseNotificationBuilder: NotificationCompat.Builder

    lateinit var curNotificationBuilder: NotificationCompat.Builder

    companion object {
        //companion içindeler çünkü LiveData'da tracking fragment'ın dışında gözlemlemek ve değiştirmek istiyoruz
        val timeRunInMillis = MutableLiveData<Long>()
        val  isTracking = MutableLiveData<Boolean>()
        val  pathPoints = MutableLiveData<Polylines>()
    }

    private fun postInitialValue (){
        isTracking.postValue(false)
        pathPoints.postValue(mutableListOf())
        timeRunInSeconds.postValue(0L)
        timeRunInMillis.postValue(0L)

    }

    override fun onCreate() {
        super.onCreate()
        curNotificationBuilder = baseNotificationBuilder
        postInitialValue()

        isTracking.observe(this, Observer {
            updateLocationTracking(it)
            updateNotificationTrackingState(it)
        })
    }

    private fun killService() {
        serviceKilled = true
        isFirstRun = true
        pauseService()
        postInitialValue()
        stopForeground(true)
        stopSelf()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when(it.action){
                ACTION_START_OR_RESUME_SERVICE -> {
                    if (isFirstRun){
                        startForegroundService()
                        isFirstRun = false
                    } else {
                        Timber.d("Resuming Service...")
                        startTimer()

                    }


                }
                ACTION_PAUSE_SERVICE -> {
                    Timber.d("Paused service")
                    pauseService()
                }
                ACTION_STOP_SERVICE -> {
                    Timber.d("Stopped service")
                    killService()
                }

            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private var isTimerEnabled = false
    private var lapTime = 0L //Her koşuştan sonra zamanı durdurup tekrar başlatınca zamanı sıfırdan başlatır.
    private var timeRun = 0L // Toplam koşulan zaman
    private var timeStarted = 0L // İlk başlangıç zamanını 0 yapar.
    private var lastSecondTimeStamp = 0L

    private fun startTimer(){
        addEmptyPolyline()
        isTracking.postValue(true)
        timeStarted = System.currentTimeMillis()
        isTimerEnabled = true
        CoroutineScope(Dispatchers.Main).launch {
            while (isTracking.value!!){
                //şimdiyle başlangıç arasındaki zaman farkı
                lapTime = System.currentTimeMillis() - timeStarted
                //şimdiki zamanı ilet
                timeRunInMillis.postValue(timeRun+lapTime)
                if (timeRunInMillis.value!! >= lastSecondTimeStamp + 1000L) {
                    timeRunInSeconds.postValue(timeRunInSeconds.value!! +1)
                    lastSecondTimeStamp += 1000L
                }
                delay(TIMER_UPDATE_INTERVAL)
            }
            timeRun += lapTime
        }
    }

    private fun pauseService(){
        isTracking.postValue(false)
        isTimerEnabled = false
    }

    private fun updateNotificationTrackingState(isTracking: Boolean) {
        val notificationActionText = if (isTracking) "Pause" else "Resume"
        val PendingIntent = if (isTracking) {
            val pauseIntent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_PAUSE_SERVICE
            }
            PendingIntent.getService(this, 1, pauseIntent, FLAG_UPDATE_CURRENT)
        } else {
            val resumeIntent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_START_OR_RESUME_SERVICE
            }
            PendingIntent.getService(this, 2, resumeIntent, FLAG_UPDATE_CURRENT)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        curNotificationBuilder.javaClass.getDeclaredField("mActions").apply {
            isAccessible = true
            set(curNotificationBuilder, ArrayList<NotificationCompat.Action>())
        }
        if (!serviceKilled){
            curNotificationBuilder = baseNotificationBuilder
                    .addAction(R.drawable.ic_pause_black_24dp, notificationActionText, PendingIntent)
            notificationManager.notify(NOTIFICATION_ID, curNotificationBuilder.build())
        }


    }





    @SuppressLint("MissingPermission")
    private fun updateLocationTracking(isTracking: Boolean){
        if(isTracking){
            if (TrackingUtility.hasLocationPermissions(this)){
                val request = LocationRequest.create().apply  {
                    interval = LOCATION_UPDATE_INTERVAL
                    fastestInterval = FASTEST_LOCATION_INTERVAL
                    priority = PRIORITY_HIGH_ACCURACY
                }
                fusedLocationProviderClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
        }else {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        }
    }

    //LocationCallBack kullanıcının lokasyonunu dinler
    // ve size enlem ve boylam olmak üzere koordinatlarını döndürür.
    val locationCallback = object : LocationCallback(){
        override fun onLocationResult(result: LocationResult?) {
            super.onLocationResult(result)
            if (isTracking.value!!){
                result?.locations?.let { locations ->
                    for (location in locations){
                        addPathPoint(location)
                        Timber.d("NEW LOCATION : ${location.latitude}, ${location.longitude} ")
                    }
                }
            }
        }
    }

        private fun addPathPoint(location:Location?){
            location?.let {
                val pos = LatLng(location.latitude, location.longitude)
                pathPoints.value?.apply {
                    last().add(pos)
                    pathPoints.postValue(this)
                }
            }
        }

        private fun addEmptyPolyline() = pathPoints.value?.apply {
            add(mutableListOf())
            pathPoints.postValue(this)
        } ?: pathPoints.postValue(mutableListOf(mutableListOf()))



        private fun startForegroundService(){
            startTimer()
            isTracking.postValue(true)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
            if (Build.VERSION.SDK_INT >=Build.VERSION_CODES.O){
                createNotificationChannel(notificationManager)
            }


            startForeground(NOTIFICATION_ID, baseNotificationBuilder.build())

            timeRunInSeconds.observe(this, Observer {
                if (!serviceKilled){
                    val notification = curNotificationBuilder
                            .setContentText(TrackingUtility.getFormattedStopWatchTime(it * 1000L))
                    notificationManager.notify(NOTIFICATION_ID, notification.build())
                }

            })
        }




        @RequiresApi(Build.VERSION_CODES.O)
        private fun  createNotificationChannel(notificationManager: NotificationManager){

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                IMPORTANCE_LOW
            )
             notificationManager.createNotificationChannel(channel)
        }
}