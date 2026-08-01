package com.example.locationapp.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.random.Random

/**
 * Мини-игра в стиле Doodle Jump.
 *
 * Игрок — грибок, который автоматически прыгает по платформам. Управление
 * наклоном телефона (акселерометр) или касанием левой/правой половины экрана.
 * Прыгнув на голову "супер Путину", игрок получает мега-прыжок и бонусные очки.
 * Игра заканчивается, если грибок падает ниже нижней границы экрана.
 *
 * Всё рисуется примитивами на Canvas — внешние ассеты не нужны.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable,
    SensorEventListener {

    // --- Игровой цикл ---
    @Volatile
    private var running = false
    private var gameThread: Thread? = null
    private val holderRef: SurfaceHolder = holder

    // --- Размеры экрана ---
    private var screenW = 0f
    private var screenH = 0f
    private val density = resources.displayMetrics.density

    private fun dp(value: Float) = value * density

    // --- Игрок (грибок) ---
    private var playerX = 0f
    private var playerY = 0f
    private var playerVelX = 0f
    private var playerVelY = 0f
    private val playerW get() = dp(46f)
    private val playerH get() = dp(48f)

    // --- Физика ---
    private val gravity get() = dp(0.55f)
    private val jumpVel get() = -dp(17f)
    private val megaJumpVel get() = -dp(26f)
    private val maxFall get() = dp(24f)

    // --- Управление наклоном ---
    private var tilt = 0f
    private var touchDir = 0f // -1 влево, +1 вправо, 0 нет касания
    @Volatile
    private var pendingReset = false
    private val moveSpeed get() = dp(1.6f)
    private val maxMoveSpeed get() = dp(14f)

    // --- Мир ---
    private val platforms = ArrayList<Platform>()
    private val putins = ArrayList<Putin>()
    private var score = 0
    private var bestScore = 0
    private var gameOver = false
    private val platformGap get() = dp(120f)

    // --- Датчики ---
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // --- Кисти для рисования ---
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(22f)
        isFakeBoldText = true
    }
    private val bigTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(40f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private var skyShader: Shader? = null

    init {
        holderRef.addCallback(this)
        isFocusable = true
    }

    private data class Platform(var x: Float, var y: Float, val w: Float)
    private data class Putin(var x: Float, var y: Float, var vx: Float, var alive: Boolean = true)

    // --- Инициализация уровня ---
    private fun resetGame() {
        platforms.clear()
        putins.clear()
        score = 0
        gameOver = false
        tilt = 0f
        touchDir = 0f

        val pw = dp(80f)
        // Стартовая платформа под игроком по центру
        var y = screenH - dp(60f)
        platforms.add(Platform(screenW / 2f - pw / 2f, y, pw))

        // Заполняем экран платформами вверх
        while (y > -platformGap) {
            y -= platformGap
            val x = Random.nextFloat() * (screenW - pw)
            platforms.add(Platform(x, y, pw))
            // Иногда добавляем "супер Путина" рядом с платформой
            if (Random.nextFloat() < 0.35f) {
                spawnPutinNear(y)
            }
        }

        playerX = screenW / 2f - playerW / 2f
        playerY = screenH - dp(60f) - playerH
        playerVelX = 0f
        playerVelY = jumpVel
    }

    private fun spawnPutinNear(y: Float) {
        val size = dp(50f)
        val x = Random.nextFloat() * (screenW - size)
        val vx = (if (Random.nextBoolean()) 1f else -1f) * dp(1.5f) * (0.5f + Random.nextFloat())
        putins.add(Putin(x, y - dp(70f), vx))
    }

    // --- Обновление игрового состояния ---
    private fun update() {
        if (pendingReset) {
            pendingReset = false
            resetGame()
            return
        }
        if (gameOver) return

        // Горизонтальное движение: наклон + касание
        val control = if (touchDir != 0f) touchDir * dp(6f) else -tilt * moveSpeed
        playerVelX += control * 0.5f
        playerVelX *= 0.9f // трение
        playerVelX = playerVelX.coerceIn(-maxMoveSpeed, maxMoveSpeed)
        playerX += playerVelX

        // Оборачивание по горизонтали (вышел за левый край — появился справа)
        if (playerX + playerW < 0) playerX = screenW
        if (playerX > screenW) playerX = -playerW

        // Гравитация
        playerVelY += gravity
        if (playerVelY > maxFall) playerVelY = maxFall
        playerY += playerVelY

        // Прокрутка мира вниз, когда игрок поднимается выше середины
        val threshold = screenH * 0.4f
        if (playerY < threshold) {
            val dy = threshold - playerY
            playerY = threshold
            score += (dy / density).toInt()
            for (p in platforms) p.y += dy
            for (pu in putins) pu.y += dy
        }

        // Столкновение с платформами (только при падении)
        if (playerVelY > 0) {
            val feetY = playerY + playerH
            for (p in platforms) {
                if (playerX + playerW * 0.8f > p.x &&
                    playerX + playerW * 0.2f < p.x + p.w &&
                    feetY > p.y && feetY < p.y + dp(24f)
                ) {
                    playerVelY = jumpVel
                    break
                }
            }
        }

        // Столкновение с "супер Путиным"
        val putinIter = putins.iterator()
        while (putinIter.hasNext()) {
            val pu = putinIter.next()
            pu.x += pu.vx
            val size = dp(50f)
            if (pu.x < 0 || pu.x + size > screenW) pu.vx = -pu.vx

            if (rectsOverlap(playerX, playerY, playerW, playerH, pu.x, pu.y, size, size)) {
                val feetY = playerY + playerH
                // Прыжок сверху на голову — мега-прыжок и очки
                if (playerVelY > 0 && feetY < pu.y + size * 0.6f) {
                    playerVelY = megaJumpVel
                    score += 100
                    putinIter.remove()
                } else {
                    // Удар сбоку/снизу — конец игры
                    endGame()
                    return
                }
            }
        }

        // Удаляем платформы, ушедшие вниз за экран, и генерируем новые сверху
        platforms.removeAll { it.y > screenH }
        putins.removeAll { it.y > screenH }
        var topY = platforms.minByOrNull { it.y }?.y ?: 0f
        val pw = dp(80f)
        while (topY > -platformGap) {
            topY -= platformGap
            val x = Random.nextFloat() * (screenW - pw)
            platforms.add(Platform(x, topY, pw))
            if (Random.nextFloat() < 0.35f) spawnPutinNear(topY)
        }

        // Падение ниже экрана — конец игры
        if (playerY > screenH) {
            endGame()
        }
    }

    private fun endGame() {
        gameOver = true
        if (score > bestScore) bestScore = score
    }

    private fun rectsOverlap(
        ax: Float, ay: Float, aw: Float, ah: Float,
        bx: Float, by: Float, bw: Float, bh: Float
    ): Boolean {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by
    }

    // --- Отрисовка ---
    private fun render() {
        val canvas = holderRef.lockCanvas() ?: return
        try {
            drawSky(canvas)
            for (p in platforms) drawPlatform(canvas, p)
            for (pu in putins) drawPutin(canvas, pu.x, pu.y, dp(50f))
            drawMushroom(canvas, playerX, playerY, playerW, playerH)
            drawHud(canvas)
            if (gameOver) drawGameOver(canvas)
        } finally {
            holderRef.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawSky(canvas: Canvas) {
        if (skyShader == null && screenH > 0) {
            skyShader = LinearGradient(
                0f, 0f, 0f, screenH,
                Color.rgb(120, 200, 255), Color.rgb(210, 245, 255),
                Shader.TileMode.CLAMP
            )
        }
        paint.shader = skyShader
        canvas.drawRect(0f, 0f, screenW, screenH, paint)
        paint.shader = null
        // Пара облаков
        paint.color = Color.argb(160, 255, 255, 255)
        canvas.drawCircle(screenW * 0.2f, screenH * 0.15f, dp(28f), paint)
        canvas.drawCircle(screenW * 0.28f, screenH * 0.15f, dp(34f), paint)
        canvas.drawCircle(screenW * 0.75f, screenH * 0.3f, dp(24f), paint)
        canvas.drawCircle(screenW * 0.82f, screenH * 0.3f, dp(30f), paint)
    }

    private fun drawPlatform(canvas: Canvas, p: Platform) {
        val h = dp(16f)
        val rect = RectF(p.x, p.y, p.x + p.w, p.y + h)
        paint.color = Color.rgb(76, 175, 80)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)
        paint.color = Color.rgb(56, 142, 60)
        canvas.drawRoundRect(
            RectF(p.x, p.y + h * 0.6f, p.x + p.w, p.y + h),
            dp(8f), dp(8f), paint
        )
    }

    private fun drawMushroom(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val cx = x + w / 2f
        // Ножка
        paint.color = Color.rgb(245, 235, 210)
        val stem = RectF(cx - w * 0.22f, y + h * 0.5f, cx + w * 0.22f, y + h)
        canvas.drawRoundRect(stem, dp(6f), dp(6f), paint)
        // Шляпка
        paint.color = Color.rgb(220, 50, 50)
        canvas.drawArc(RectF(x, y, x + w, y + h), 180f, 180f, true, paint)
        canvas.drawRect(RectF(x, y + h * 0.45f, x + w, y + h * 0.55f), paint)
        // Белые пятнышки
        paint.color = Color.WHITE
        canvas.drawCircle(cx - w * 0.22f, y + h * 0.28f, dp(5f), paint)
        canvas.drawCircle(cx + w * 0.2f, y + h * 0.32f, dp(4f), paint)
        canvas.drawCircle(cx, y + h * 0.15f, dp(3.5f), paint)
        // Глаза
        paint.color = Color.BLACK
        canvas.drawCircle(cx - w * 0.14f, y + h * 0.66f, dp(3f), paint)
        canvas.drawCircle(cx + w * 0.14f, y + h * 0.66f, dp(3f), paint)
    }

    /** Карикатурный "супер Путин" — стилизованная лысая голова в костюме с плащом. */
    private fun drawPutin(canvas: Canvas, x: Float, y: Float, size: Float) {
        val cx = x + size / 2f
        // Плащ супергероя
        paint.color = Color.rgb(200, 30, 30)
        canvas.drawArc(
            RectF(x - size * 0.2f, y + size * 0.55f, x + size * 1.2f, y + size * 1.3f),
            0f, 180f, true, paint
        )
        // Костюм
        paint.color = Color.rgb(40, 50, 80)
        canvas.drawRect(RectF(x + size * 0.15f, y + size * 0.6f, x + size * 0.85f, y + size), paint)
        // Голова (лысая)
        paint.color = Color.rgb(240, 210, 180)
        canvas.drawOval(RectF(x + size * 0.15f, y, x + size * 0.85f, y + size * 0.7f), paint)
        // Брови (серьёзные)
        paint.color = Color.rgb(120, 100, 90)
        paint.strokeWidth = dp(3f)
        canvas.drawLine(cx - size * 0.22f, y + size * 0.28f, cx - size * 0.06f, y + size * 0.3f, paint)
        canvas.drawLine(cx + size * 0.22f, y + size * 0.28f, cx + size * 0.06f, y + size * 0.3f, paint)
        // Глаза
        paint.color = Color.BLACK
        canvas.drawCircle(cx - size * 0.13f, y + size * 0.36f, dp(2.5f), paint)
        canvas.drawCircle(cx + size * 0.13f, y + size * 0.36f, dp(2.5f), paint)
        // Рот
        paint.strokeWidth = dp(2f)
        canvas.drawLine(cx - size * 0.1f, y + size * 0.52f, cx + size * 0.1f, y + size * 0.52f, paint)
        // Значок "П" на груди
        textPaint.textSize = dp(14f)
        textPaint.color = Color.YELLOW
        canvas.drawText("П", cx - dp(6f), y + size * 0.9f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = dp(22f)
    }

    private fun drawHud(canvas: Canvas) {
        canvas.drawText("Очки: $score", dp(16f), dp(40f), textPaint)
        canvas.drawText("Рекорд: $bestScore", dp(16f), dp(70f), textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        paint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRect(0f, 0f, screenW, screenH, paint)
        bigTextPaint.textSize = dp(40f)
        canvas.drawText("Игра окончена", screenW / 2f, screenH * 0.4f, bigTextPaint)
        bigTextPaint.textSize = dp(26f)
        canvas.drawText("Очки: $score", screenW / 2f, screenH * 0.4f + dp(50f), bigTextPaint)
        canvas.drawText("Рекорд: $bestScore", screenW / 2f, screenH * 0.4f + dp(90f), bigTextPaint)
        bigTextPaint.textSize = dp(22f)
        canvas.drawText("Нажми, чтобы начать заново", screenW / 2f, screenH * 0.4f + dp(150f), bigTextPaint)
    }

    // --- Игровой поток ---
    override fun run() {
        val targetFrame = 1000L / 60L
        while (running) {
            val start = System.currentTimeMillis()
            if (screenW > 0 && screenH > 0) {
                update()
                render()
            }
            val elapsed = System.currentTimeMillis() - start
            val sleep = targetFrame - elapsed
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    fun resume() {
        running = true
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gameThread = Thread(this).also { it.start() }
    }

    fun pause() {
        running = false
        sensorManager.unregisterListener(this)
        try {
            gameThread?.join()
        } catch (_: InterruptedException) {
        }
        gameThread = null
    }

    // --- SurfaceHolder.Callback ---
    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        skyShader = null
        if (platforms.isEmpty()) resetGame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    // --- Ввод ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (gameOver) {
                    pendingReset = true
                    return true
                }
                touchDir = if (event.x < screenW / 2f) -1f else 1f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchDir = 0f
            }
        }
        return true
    }

    // --- Акселерометр ---
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            tilt = if (abs(x) > 0.3f) x else 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
