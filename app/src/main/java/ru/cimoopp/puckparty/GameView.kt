package ru.cimoopp.puckparty

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val color: Int,
    val size: Float
)

private class Label(
    var x: Float,
    var y: Float,
    var life: Float,
    val text: String,
    val color: Int,
    val size: Float
)

/**
 * Отрисовка и управление. Мультяшный аэрохоккей:
 * лёд, оранжевые борта, красная ракетка игрока и фиолетовая у компьютера.
 */
class GameView(context: Context) : View(context) {

    private enum class State { MENU, PLAY, PAUSE, OVER }

    private val engine = GameEngine()
    private var state = State.MENU

    private var scaleF = 1f
    private var offX = 0f
    private var offY = 0f

    private var lastFrame = 0L
    private var time = 0f
    private var shake = 0f
    private var goalLabel = ""
    private var goalLabelLife = 0f
    private var playerWon = false

    private val particles = ArrayList<Particle>()
    private val labels = ArrayList<Label>()

    private val trailX = FloatArray(14)
    private val trailY = FloatArray(14)

    private var dragging = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var malletStartX = 0f
    private var malletStartY = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val strokeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val ink = 0xFF2A1B14.toInt()
    private val redTeam = 0xFFFF3B4E.toInt()
    private val redTeamDark = 0xFFB0233A.toInt()
    private val purpleTeam = 0xFF9B51FF.toInt()
    private val purpleTeamDark = 0xFF5F2AB0.toInt()

    init {
        isFocusable = true

        engine.onGoal = { byPlayer ->
            goalLabel = if (byPlayer) "ГОЛ!" else "ПРОПУСТИЛ"
            goalLabelLife = 1.1f
            shake = 26f
            buzz(110)
            val gx = if (byPlayer) GameEngine.W - GameEngine.WALL else GameEngine.WALL
            spawnSparks(gx, GameEngine.H / 2f, 520f, 26)
            if (engine.scorePlayer >= GameEngine.MAX_SCORE || engine.scoreAi >= GameEngine.MAX_SCORE) {
                playerWon = engine.scorePlayer > engine.scoreAi
                state = State.OVER
            }
        }

        engine.onHit = { x, y, power ->
            spawnSparks(x, y, power, 10)
            buzz(16)
        }

        engine.onWall = { x, y ->
            spawnSparks(x, y, 170f, 5)
            buzz(8)
        }
    }

    // ---------- жизненный цикл ----------

    fun onResumed() {
        lastFrame = 0L
    }

    fun onPaused() {
        if (state == State.PLAY) state = State.PAUSE
        dragging = false
    }

    fun onBackPressedInGame(): Boolean = when (state) {
        State.PLAY -> { state = State.PAUSE; true }
        State.PAUSE -> { state = State.MENU; true }
        State.OVER -> { state = State.MENU; true }
        State.MENU -> false
    }

    // ---------- геометрия экрана ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scaleF = min(w / GameEngine.W, h / GameEngine.H)
        offX = (w - GameEngine.W * scaleF) / 2f
        offY = (h - GameEngine.H * scaleF) / 2f
    }

    // ---------- игровой цикл ----------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        if (lastFrame == 0L) lastFrame = now
        var dt = (now - lastFrame) / 1_000_000_000f
        lastFrame = now
        if (dt > 0.05f) dt = 0.05f

        tick(dt)
        render(canvas)
        postInvalidateOnAnimation()
    }

    private fun tick(dt: Float) {
        time += dt
        if (shake > 0f) shake = (shake - dt * 70f).coerceAtLeast(0f)
        if (goalLabelLife > 0f) goalLabelLife -= dt

        if (state == State.PLAY) engine.update(dt)

        for (i in 0 until trailX.size - 1) {
            trailX[i] = trailX[i + 1]
            trailY[i] = trailY[i + 1]
        }
        trailX[trailX.size - 1] = engine.puckX
        trailY[trailY.size - 1] = engine.puckY

        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            val k = (1f - 2.3f * dt).coerceIn(0f, 1f)
            p.vx *= k
            p.vy *= k
        }

        val li = labels.iterator()
        while (li.hasNext()) {
            val l = li.next()
            l.life -= dt
            l.y -= 34f * dt
            if (l.life <= 0f) li.remove()
        }
    }

    // ---------- отрисовка ----------

    private fun render(canvas: Canvas) {
        canvas.drawColor(0xFF150C28.toInt())
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scaleF, scaleF)

        if (shake > 0.5f) {
            canvas.translate(
                (Random.nextFloat() - 0.5f) * shake,
                (Random.nextFloat() - 0.5f) * shake
            )
        }

        drawBackground(canvas)
        drawTable(canvas)

        if (state != State.MENU) {
            drawTrail(canvas)
            drawPuck(canvas)
            drawMallet(canvas, engine.playerX, engine.playerY, redTeam, redTeamDark)
            drawMallet(canvas, engine.aiX, engine.aiY, purpleTeam, purpleTeamDark)
            drawScore(canvas)
        }

        drawParticles(canvas)

        when (state) {
            State.MENU -> drawMenu(canvas)
            State.PAUSE -> drawPause(canvas)
            State.OVER -> drawOver(canvas)
            State.PLAY -> drawGoalLabel(canvas)
        }

        drawLabels(canvas)
        canvas.restore()
    }

    private fun drawBackground(c: Canvas) {
        fillPaint.shader = LinearGradient(
            0f, 0f, GameEngine.W, GameEngine.H,
            0xFF1E1140.toInt(), 0xFF3A1B5C.toInt(), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, GameEngine.W, GameEngine.H, fillPaint)
        fillPaint.shader = null

        fillPaint.color = 0x14FFFFFF
        var x = -GameEngine.H
        while (x < GameEngine.W) {
            c.save()
            c.rotate(-18f, GameEngine.W / 2f, GameEngine.H / 2f)
            c.drawRect(x, -400f, x + 42f, GameEngine.H + 400f, fillPaint)
            c.restore()
            x += 96f
        }
    }

    private fun drawTable(c: Canvas) {
        // тёмная окантовка стола
        fillPaint.shader = null
        fillPaint.color = 0xFF1A0E2E.toInt()
        c.drawRoundRect(RectF(6f, 10f, GameEngine.W - 6f, GameEngine.H - 2f), 52f, 52f, fillPaint)

        // оранжевый борт
        fillPaint.color = 0xFFFFA62B.toInt()
        c.drawRoundRect(RectF(0f, 0f, GameEngine.W, GameEngine.H), 50f, 50f, fillPaint)

        fillPaint.color = 0xFFE07B12.toInt()
        c.drawRoundRect(
            RectF(GameEngine.WALL - 4f, GameEngine.WALL - 4f,
                GameEngine.W - GameEngine.WALL + 4f, GameEngine.H - GameEngine.WALL + 4f),
            40f, 40f, fillPaint
        )

        // лёд
        fillPaint.shader = LinearGradient(
            0f, GameEngine.WALL, 0f, GameEngine.H - GameEngine.WALL,
            0xFFEFFBFF.toInt(), 0xFF8FD3FF.toInt(), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(
            RectF(GameEngine.WALL, GameEngine.WALL,
                GameEngine.W - GameEngine.WALL, GameEngine.H - GameEngine.WALL),
            34f, 34f, fillPaint
        )
        fillPaint.shader = null

        // голубые «половины»
        fillPaint.color = 0x14225599
        c.drawRoundRect(
            RectF(GameEngine.WALL, GameEngine.WALL, GameEngine.W / 2f - 3f, GameEngine.H - GameEngine.WALL),
            34f, 34f, fillPaint
        )
        fillPaint.color = 0x14FF3B4E
        c.drawRoundRect(
            RectF(GameEngine.W / 2f + 3f, GameEngine.WALL, GameEngine.W - GameEngine.WALL, GameEngine.H - GameEngine.WALL),
            34f, 34f, fillPaint
        )

        // центральная линия
        linePaint.color = 0x5590B8D8
        linePaint.strokeWidth = 5f
        linePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 18f), 0f)
        c.drawLine(GameEngine.W / 2f, GameEngine.WALL + 8f, GameEngine.W / 2f, GameEngine.H - GameEngine.WALL - 8f, linePaint)
        linePaint.pathEffect = null

        // центральный круг
        linePaint.color = 0x5590B8D8
        linePaint.strokeWidth = 5f
        c.drawCircle(GameEngine.W / 2f, GameEngine.H / 2f, 96f, linePaint)

        // ворота
        drawGoal(c, true, redTeam, redTeamDark)
        drawGoal(c, false, purpleTeam, purpleTeamDark)

        // подписи сторон
        textPaint.textSize = 34f
        textPaint.color = 0x55335577
        c.drawText("ВЫ", GameEngine.W / 4f, GameEngine.H - GameEngine.WALL - 26f, textPaint)
        textPaint.color = 0x55AA3355
        c.drawText("КОМПЬЮТЕР", GameEngine.W * 0.75f, GameEngine.H - GameEngine.WALL - 26f, textPaint)
    }

    private fun drawGoal(c: Canvas, left: Boolean, color: Int, dark: Int) {
        val top = GameEngine.H / 2f - GameEngine.GOAL_HALF
        val bottom = GameEngine.H / 2f + GameEngine.GOAL_HALF
        val rect = if (left) {
            RectF(2f, top, GameEngine.WALL + 10f, bottom)
        } else {
            RectF(GameEngine.W - GameEngine.WALL - 10f, top, GameEngine.W - 2f, bottom)
        }

        fillPaint.shader = null
        fillPaint.color = dark
        c.drawRoundRect(rect, 12f, 12f, fillPaint)

        fillPaint.color = color
        val inner = RectF(rect)
        inner.inset(6f, 9f)
        c.drawRoundRect(inner, 9f, 9f, fillPaint)

        linePaint.color = 0x66FFFFFF
        linePaint.strokeWidth = 3f
        var y = top + 22f
        while (y < bottom - 12f) {
            c.drawLine(inner.left + 3f, y, inner.right - 3f, y, linePaint)
            y += 22f
        }
    }

    private fun drawTrail(c: Canvas) {
        val speed = kotlin.math.sqrt(engine.puckVX * engine.puckVX + engine.puckVY * engine.puckVY)
        if (speed < 90f) return
        for (i in 0 until trailX.size - 1) {
            val k = i.toFloat() / (trailX.size - 1)
            fillPaint.shader = null
            fillPaint.color = (0x22FFFFFF * k.toInt()).toInt()
            c.drawCircle(trailX[i], trailY[i], GameEngine.PUCK_R * (0.35f + 0.6f * k), fillPaint)
        }
    }

    private fun drawPuck(c: Canvas) {
        val x = engine.puckX
        val y = engine.puckY
        val r = GameEngine.PUCK_R

        // тень
        fillPaint.shader = null
        fillPaint.color = 0x44000000
        c.drawOval(RectF(x - r * 0.95f, y - r * 0.6f + 12f, x + r * 0.95f, y + r * 0.6f + 12f), fillPaint)

        // корпус
        fillPaint.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.4f, r * 1.5f,
            0xFF4A4F5C.toInt(), 0xFF16181E.toInt(), Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r, fillPaint)
        fillPaint.shader = null

        linePaint.color = ink
        linePaint.strokeWidth = 5f
        c.drawCircle(x, y, r, linePaint)

        fillPaint.color = 0xFF2B2F38.toInt()
        c.drawCircle(x, y, r * 0.62f, fillPaint)

        fillPaint.color = 0x99FFFFFF.toInt()
        c.drawCircle(x - r * 0.32f, y - r * 0.36f, r * 0.26f, fillPaint)
    }

    private fun drawMallet(c: Canvas, x: Float, y: Float, color: Int, dark: Int) {
        val r = GameEngine.MALLET_R

        // тень
        fillPaint.shader = null
        fillPaint.color = 0x44000000
        c.drawOval(RectF(x - r * 0.95f, y - r * 0.55f + 14f, x + r * 0.95f, y + r * 0.55f + 14f), fillPaint)

        // свечение
        fillPaint.color = (color and 0x00FFFFFF) or 0x33000000
        c.drawCircle(x, y, r * 1.16f, fillPaint)

        // корпус
        fillPaint.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.4f, r * 1.6f,
            color, dark, Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r, fillPaint)
        fillPaint.shader = null

        linePaint.color = ink
        linePaint.strokeWidth = 6f
        c.drawCircle(x, y, r, linePaint)

        fillPaint.color = 0xFF2B2F38.toInt()
        c.drawCircle(x, y, r * 0.45f, fillPaint)

        fillPaint.color = 0x88FFFFFF.toInt()
        c.drawCircle(x - r * 0.34f, y - r * 0.38f, r * 0.18f, fillPaint)
    }

    private fun drawScore(c: Canvas) {
        val rect = RectF(GameEngine.W / 2f - 210f, 16f, GameEngine.W / 2f + 210f, 92f)
        fillPaint.shader = null
        fillPaint.color = 0xCC1B1030.toInt()
        c.drawRoundRect(rect, 30f, 30f, fillPaint)
        linePaint.color = 0x66FFFFFF
        linePaint.strokeWidth = 4f
        c.drawRoundRect(rect, 30f, 30f, linePaint)

        textPaint.textSize = 46f
        textPaint.color = redTeam
        textPaint.textAlign = Paint.Align.RIGHT
        c.drawText(engine.scorePlayer.toString(), GameEngine.W / 2f - 96f, 74f, textPaint)

        textPaint.color = 0xFFFFFFFF.toInt()
        c.drawText(":", GameEngine.W / 2f, 74f, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = purpleTeam
        c.drawText(engine.scoreAi.toString(), GameEngine.W / 2f + 96f, 74f, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 22f
        textPaint.color = 0xAAFFFFFF.toInt()
        c.drawText("${engine.difficulty.title} • до ${GameEngine.MAX_SCORE}", GameEngine.W / 2f, 112f, textPaint)
    }

    private fun drawGoalLabel(c: Canvas) {
        if (goalLabelLife <= 0f) return
        val k = goalLabelLife.coerceIn(0f, 1f)
        val size = 120f + (1f - k) * 30f
        textPaint.textSize = size
        textPaint.color = (0xFF00E0A0.toInt() and 0x00FFFFFF) or ((k * 255).toInt() shl 24)
        titleStroke("$goalLabel", GameEngine.W / 2f, GameEngine.H / 2f + 30f, size, textPaint.color, 12f)
    }

    private fun drawParticles(c: Canvas) {
        for (p in particles) {
            val k = (p.life / 0.8f).coerceIn(0f, 1f)
            fillPaint.shader = null
            fillPaint.color = (p.color and 0x00FFFFFF) or ((k * 255).toInt() shl 24)
            c.drawCircle(p.x, p.y, p.size * (0.4f + k * 0.8f), fillPaint)
        }
    }

    private fun drawLabels(c: Canvas) {
        for (l in labels) {
            val k = (l.life / 0.9f).coerceIn(0f, 1f)
            textPaint.textSize = l.size
            textPaint.color = (l.color and 0x00FFFFFF) or ((k * 255).toInt() shl 24)
            c.drawText(l.text, l.x, l.y, textPaint)
        }
    }

    // ---------- экраны ----------

    private fun drawMenu(c: Canvas) {
        fillPaint.shader = null
        fillPaint.color = 0x88150C28.toInt()
        c.drawRect(0f, 0f, GameEngine.W, GameEngine.H, fillPaint)

        val bounce = sin(time * 2.2f) * 8f
        title("PUCK", GameEngine.W / 2f - 118f, 128f + bounce, 92f, 0xFFFFE066.toInt())
        title("PARTY", GameEngine.W / 2f + 132f, 128f + bounce, 92f, 0xFFFF6B8A.toInt())

        textPaint.textSize = 26f
        textPaint.color = 0xCCFFFFFF.toInt()
        c.drawText("мультяшный аэрохоккей на двоих", GameEngine.W / 2f, 172f + bounce, textPaint)

        button(c, 300f, 210f, 700f, 286f, "ИГРАТЬ С КОМПЬЮТЕРОМ", redTeam, 32f)
        button(c, 300f, 302f, 700f, 374f, "СЛОЖНОСТЬ: ${engine.difficulty.title.uppercase()}", 0xFFF2A03D.toInt(), 30f)
        button(c, 300f, 390f, 700f, 462f, "BLUETOOTH-ДУЭЛЬ — СКОРО", 0xFF8A8FA0.toInt(), 26f, enabled = false)

        textPaint.textSize = 24f
        textPaint.color = 0x99FFFFFF.toInt()
        c.drawText("Ведите палец по своей (левой) половине стола", GameEngine.W / 2f, 522f, textPaint)
        textPaint.textSize = 20f
        textPaint.color = 0x66FFFFFF.toInt()
        c.drawText("Первый до ${GameEngine.MAX_SCORE} голов", GameEngine.W / 2f, 552f, textPaint)
    }

    private fun drawPause(c: Canvas) {
        fillPaint.shader = null
        fillPaint.color = 0xAA120A22.toInt()
        c.drawRect(0f, 0f, GameEngine.W, GameEngine.H, fillPaint)

        title("ПАУЗА", GameEngine.W / 2f, 210f, 74f, 0xFFFFE066.toInt())
        button(c, 320f, 280f, 680f, 356f, "ПРОДОЛЖИТЬ", 0xFF2ECC71.toInt(), 32f)
        button(c, 320f, 374f, 680f, 448f, "В МЕНЮ", 0xFF6C7A96.toInt(), 30f)
    }

    private fun drawOver(c: Canvas) {
        fillPaint.shader = null
        fillPaint.color = 0xBB120A22.toInt()
        c.drawRect(0f, 0f, GameEngine.W, GameEngine.H, fillPaint)

        val panel = RectF(250f, 110f, 750f, 500f)
        fillPaint.color = 0xFF241443.toInt()
        c.drawRoundRect(panel, 40f, 40f, fillPaint)
        linePaint.color = 0x66FFFFFF
        linePaint.strokeWidth = 5f
        c.drawRoundRect(panel, 40f, 40f, linePaint)

        val headColor = if (playerWon) 0xFF2ECC71.toInt() else 0xFFFF6B8A.toInt()
        title(if (playerWon) "ПОБЕДА!" else "ПОРАЖЕНИЕ", GameEngine.W / 2f, 210f, 78f, headColor)

        textPaint.textSize = 56f
        textPaint.color = 0xFFFFFFFF.toInt()
        c.drawText("${engine.scorePlayer} : ${engine.scoreAi}", GameEngine.W / 2f, 300f, textPaint)

        textPaint.textSize = 24f
        textPaint.color = 0xAAFFFFFF.toInt()
        c.drawText(if (playerWon) "Компьютер повержен" else "Компьютер оказался быстрее", GameEngine.W / 2f, 340f, textPaint)

        button(c, 320f, 366f, 680f, 436f, "ЕЩЁ РАЗ", redTeam, 32f)
        button(c, 320f, 446f, 680f, 500f, "В МЕНЮ", 0xFF6C7A96.toInt(), 26f)
    }

    private fun button(
        c: Canvas,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        label: String,
        color: Int,
        textSize: Float,
        enabled: Boolean = true
    ) {
        fillPaint.shader = null
        fillPaint.color = 0x55000000
        c.drawRoundRect(RectF(l, t + 9f, r, b + 9f), 30f, 30f, fillPaint)

        fillPaint.color = if (enabled) color else 0xFF848A99.toInt()
        c.drawRoundRect(RectF(l, t, r, b), 30f, 30f, fillPaint)

        linePaint.color = ink
        linePaint.strokeWidth = 7f
        c.drawRoundRect(RectF(l, t, r, b), 30f, 30f, linePaint)

        fillPaint.color = 0x44FFFFFF
        c.drawRoundRect(RectF(l + 16f, t + 11f, r - 16f, t + 38f), 18f, 18f, fillPaint)

        textPaint.textSize = textSize
        textPaint.color = if (enabled) 0xFFFFFFFF.toInt() else 0xFFEDEFF3.toInt()
        c.drawText(label, (l + r) / 2f, (t + b) / 2f + textSize * 0.35f, textPaint)
    }

    private fun title(s: String, x: Float, y: Float, size: Float, color: Int) {
        titleStroke(s, x, y, size, color, 12f)
    }

    private fun titleStroke(s: String, x: Float, y: Float, size: Float, color: Int, strokeW: Float) {
        textPaint.textSize = size
        textPaint.color = color
        strokeTextPaint.textSize = size
        strokeTextPaint.strokeWidth = strokeW
        strokeTextPaint.color = ink
        strokeTextPaint.textAlign = Paint.Align.CENTER
        c_drawStroke(s, x, y)
        c_drawFill(s, x, y)
    }

    private fun c_drawStroke(s: String, x: Float, y: Float) {
        pendingStroke = Triple(s, x, y)
    }

    private fun c_drawFill(s: String, x: Float, y: Float) {
        // отрисовка выполняется в renderFrame
        pendingFill = Triple(s, x, y)
    }

    private var pendingStroke: Triple<String, Float, Float>? = null
    private var pendingFill: Triple<String, Float, Float>? = null

    // ---------- ввод ----------

    private fun hit(x: Float, y: Float, l: Float, t: Float, r: Float, b: Float) =
        x >= l && x <= r && y >= t && y <= b

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val gx = (event.x - offX) / scaleF
        val gy = (event.y - offY) / scaleF

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> when (state) {
                State.MENU -> handleMenuTap(gx, gy)
                State.PLAY -> {
                    dragging = true
                    touchStartX = gx
                    touchStartY = gy
                    malletStartX = engine.playerX
                    malletStartY = engine.playerY
                }
                State.PAUSE -> handlePauseTap(gx, gy)
                State.OVER -> handleOverTap(gx, gy)
            }

            MotionEvent.ACTION_MOVE -> if (dragging && state == State.PLAY) {
                engine.movePlayerTo(
                    malletStartX + (gx - touchStartX),
                    malletStartY + (gy - touchStartY)
                )
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun handleMenuTap(x: Float, y: Float) {
        when {
            hit(x, y, 300f, 210f, 700f, 286f) -> {
                engine.startGame()
                state = State.PLAY
                dragging = false
            }
            hit(x, y, 300f, 302f, 700f, 374f) -> {
                engine.difficulty = when (engine.difficulty) {
                    Difficulty.EASY -> Difficulty.NORMAL
                    Difficulty.NORMAL -> Difficulty.HARD
                    Difficulty.HARD -> Difficulty.EASY
                }
            }
        }
    }

    private fun handlePauseTap(x: Float, y: Float) {
        when {
            hit(x, y, 320f, 280f, 680f, 356f) -> state = State.PLAY
            hit(x, y, 320f, 374f, 680f, 448f) -> state = State.MENU
        }
    }

    private fun handleOverTap(x: Float, y: Float) {
        when {
            hit(x, y, 320f, 366f, 680f, 436f) -> {
                engine.startGame()
                state = State.PLAY
            }
            hit(x, y, 320f, 446f, 680f, 500f) -> state = State.MENU
        }
    }

    // ---------- мелочи ----------

    private fun spawnSparks(x: Float, y: Float, power: Float, count: Int) {
        for (i in 0 until count) {
            val a = Random.nextFloat() * 6.2832f
            val sp = 70f + Random.nextFloat() * (60f + power * 0.9f)
            particles.add(
                Particle(
                    x, y,
                    cos(a) * sp, sin(a) * sp,
                    0.35f + Random.nextFloat() * 0.45f,
                    if (Random.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFFFFE066.toInt(),
                    4f + Random.nextFloat() * 7f
                )
            )
        }
        while (particles.size > 300) particles.removeAt(0)
    }

    private fun buzz(ms: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }
}
