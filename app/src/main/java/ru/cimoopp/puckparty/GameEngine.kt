package ru.cimoopp.puckparty

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Уровни сложности компьютера. */
enum class Difficulty(val title: String, val maxSpeed: Float, val smart: Float) {
    EASY("Лёгкий", 430f, 0.35f),
    NORMAL("Средний", 640f, 0.60f),
    HARD("Сложный", 900f, 0.90f)
}

/**
 * Вся математика аэрохоккея.
 * Мир игры — прямоугольник 1000 x 600, экран просто масштабирует его под себя.
 */
class GameEngine {

    companion object {
        const val W = 1000f
        const val H = 600f
        const val MALLET_R = 62f
        const val PUCK_R = 26f
        const val WALL = 18f
        const val GOAL_HALF = 105f
        const val MAX_SCORE = 7
        const val MAX_SPEED = 1450f
    }

    var difficulty = Difficulty.NORMAL

    var scorePlayer = 0
    var scoreAi = 0

    var puckX = W / 2f
    var puckY = H / 2f
    var puckVX = 0f
    var puckVY = 0f

    var playerX = 230f
    var playerY = H / 2f

    var aiX = 770f
    var aiY = H / 2f

    private var prevPlayerX = playerX
    private var prevPlayerY = playerY
    private var playerVX = 0f
    private var playerVY = 0f

    private var aiVX = 0f
    private var aiVY = 0f
    private var aiTime = 0f

    /** Пауза после гола, чтобы все успели понять, что произошло. */
    private var cooldown = 0f

    var onHit: ((Float, Float, Float) -> Unit)? = null
    var onWall: ((Float, Float) -> Unit)? = null
    var onGoal: ((Boolean) -> Unit)? = null

    fun startGame() {
        scorePlayer = 0
        scoreAi = 0
        playerX = 230f
        playerY = H / 2f
        prevPlayerX = playerX
        prevPlayerY = playerY
        aiX = 770f
        aiY = H / 2f
        aiVX = 0f
        aiVY = 0f
        centerPuck(0f, 0f)
        cooldown = 0.5f
    }

    private fun centerPuck(vx: Float, vy: Float) {
        puckX = W / 2f
        puckY = H / 2f
        puckVX = vx
        puckVY = vy
    }

    /** Перемещение ракетки игрока (палец на его половине). */
    fun movePlayerTo(x: Float, y: Float) {
        val minX = WALL + MALLET_R
        val maxX = W / 2f - MALLET_R * 0.35f
        val minY = WALL + MALLET_R
        val maxY = H - WALL - MALLET_R
        playerX = x.coerceIn(minX, maxX)
        playerY = y.coerceIn(minY, maxY)
    }

    fun update(dt: Float) {
        if (cooldown > 0f) {
            cooldown -= dt
            playerVX = 0f
            playerVY = 0f
            prevPlayerX = playerX
            prevPlayerY = playerY
            return
        }

        // скорость ракетки игрока — по её смещению за кадр
        playerVX = (playerX - prevPlayerX) / dt
        playerVY = (playerY - prevPlayerY) / dt
        prevPlayerX = playerX
        prevPlayerY = playerY

        updateAi(dt)

        puckX += puckVX * dt
        puckY += puckVY * dt

        // трение льда
        val damp = (1f - 0.42f * dt).coerceIn(0f, 1f)
        puckVX *= damp
        puckVY *= damp
        if (abs(puckVX) < 4f) puckVX = 0f
        if (abs(puckVY) < 4f) puckVY = 0f

        collideWalls()
        collideMallet(playerX, playerY, playerVX, playerVY)
        collideMallet(aiX, aiY, aiVX, aiVY)
        checkGoal()
    }

    private fun updateAi(dt: Float) {
        val d = difficulty
        val homeX = W - WALL - MALLET_R - 34f
        val puckComing = puckVX > 30f
        val inAiZone = puckX > W * 0.42f

        var targetX: Float
        var targetY: Float

        when {
            inAiZone && puckComing -> {
                // перехват: встаём за шайбой и чуть отступаем к своим воротам
                targetX = puckX + MALLET_R * 0.55f
                targetY = puckY + puckVY * 0.14f * d.smart
            }
            inAiZone -> {
                // шайба рядом и медленная — подходим и подталкиваем к центру
                targetX = puckX + MALLET_R * 0.80f
                targetY = puckY + (puckY - H / 2f) * 0.30f
            }
            else -> {
                // отдыхаем у ворот, следя за шайбой по вертикали
                targetX = homeX
                targetY = H / 2f + (puckY - H / 2f) * 0.55f
            }
        }

        targetX = targetX.coerceIn(W / 2f + MALLET_R * 0.3f, homeX)
        targetY = targetY.coerceIn(WALL + MALLET_R, H - WALL - MALLET_R)

        // чем ниже сложность, тем сильнее компьютер «мажет»
        aiTime += dt
        val miss = (1f - d.smart) * 130f
        val wobble = (sin(aiTime * 1.9f) + sin(aiTime * 0.61f) * 0.6f) * miss

        val tx = targetX
        val ty = (targetY + wobble).coerceIn(WALL + MALLET_R, H - WALL - MALLET_R)

        val prevX = aiX
        val prevY = aiY
        val dx = tx - aiX
        val dy = ty - aiY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > 0.6f) {
            val step = d.maxSpeed * dt
            val k = if (step >= dist) 1f else step / dist
            aiX += dx * k
            aiY += dy * k
            aiVX = (aiX - prevX) / dt
            aiVY = (aiY - prevY) / dt
        } else {
            aiVX = 0f
            aiVY = 0f
        }
    }

    private fun collideWalls() {
        // верхний и нижний борта
        if (puckY - PUCK_R < WALL) {
            puckY = WALL + PUCK_R
            puckVY = abs(puckVY) * 0.94f
            onWall?.invoke(puckX, puckY)
        }
        if (puckY + PUCK_R > H - WALL) {
            puckY = H - WALL - PUCK_R
            puckVY = -abs(puckVY) * 0.94f
            onWall?.invoke(puckX, puckY)
        }
        // боковые борта — но в створе ворот шайба проходит
        if (puckX - PUCK_R < WALL && abs(puckY - H / 2f) >= GOAL_HALF) {
            puckX = WALL + PUCK_R
            puckVX = abs(puckVX) * 0.94f
            onWall?.invoke(puckX, puckY)
        }
        if (puckX + PUCK_R > W - WALL && abs(puckY - H / 2f) >= GOAL_HALF) {
            puckX = W - WALL - PUCK_R
            puckVX = -abs(puckVX) * 0.94f
            onWall?.invoke(puckX, puckY)
        }
    }

    private fun collideMallet(mx: Float, my: Float, mvx: Float, mvy: Float) {
        val dx = puckX - mx
        val dy = puckY - my
        val dist = sqrt(dx * dx + dy * dy)
        val minDist = MALLET_R + PUCK_R
        if (dist >= minDist) return

        val nx = if (dist > 0.01f) dx / dist else 1f
        val ny = if (dist > 0.01f) dy / dist else 0f

        // выталкиваем шайбу из ракетки
        puckX = mx + nx * (minDist + 1f)
        puckY = my + ny * (minDist + 1f)

        val pace = sqrt(puckVX * puckVX + puckVY * puckVY)
        val impulse = 430f + pace * 0.30f
        puckVX = nx * impulse + mvx * 0.85f
        puckVY = ny * impulse + mvy * 0.85f

        limitSpeed()
        onHit?.invoke(puckX, puckY, impulse)
    }

    private fun limitSpeed() {
        val s = sqrt(puckVX * puckVX + puckVY * puckVY)
        if (s > MAX_SPEED) {
            puckVX = puckVX / s * MAX_SPEED
            puckVY = puckVY / s * MAX_SPEED
        }
    }

    private fun checkGoal() {
        if (abs(puckY - H / 2f) >= GOAL_HALF) return

        if (puckX < WALL) {
            // пропустил игрок
            scoreAi++
            onGoal?.invoke(false)
            cooldown = 1.15f
            centerPuck(330f, (Math.random().toFloat() - 0.5f) * 160f)
        } else if (puckX > W - WALL) {
            // пропустил компьютер
            scorePlayer++
            onGoal?.invoke(true)
            cooldown = 1.15f
            centerPuck(-330f, (Math.random().toFloat() - 0.5f) * 160f)
        }
    }
}
