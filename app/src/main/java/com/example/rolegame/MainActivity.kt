package com.example.rolegame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

data class RoleConfig(
    val key: String, val name: String, val emoji: String,
    val hp: Float, val speed: Float, val size: Float,
    val color: Int, val passiveDesc: String
)

val ROLES = listOf(
    RoleConfig("death",  "大司命", "D", 800f, 3f,   90f, 0xFFFF4444.toInt(), "8%斩杀"),
    RoleConfig("galo",   "司空震", "G", 800f, 2.5f, 84f, 0xFFFFAA33.toInt(), "残血护盾"),
    RoleConfig("laobai", "牢白",   "L", 600f, 3f,   80f, 0xFF44DDFF.toInt(), "隐身受击分身"),
    RoleConfig("jiaotou","老教头", "J", 800f, 3.3f, 88f, 0xFFFF8844.toInt(), "减伤反伤")
)

class Unit(val role: RoleConfig, var team: Int, var x: Float, var y: Float) {
    var hp = role.hp
    val maxHp = role.hp
    var vx = 0f
    var vy = 0f
    var cd = 0
    var attackTimer = 0
    var dead = false

    var reflectActive = false
    var reflectTimer = 0
    var reflectCooldown = 0
    var reduceActive = false
    var reduceTimer = 0
    var reduceCooldown = 0
    var speedBoostActive = false
    var speedBoostTimer = 0
    var lowHpBerserk = false
    var shield = 0f
    var shieldActive = false
    var shieldDuration = 0
    var shieldTriggered = false
    var invincible = false
    var invincibleTimer = 0
    var invincibleCooldown = 0
    var dashTimer = 0
    var isPlayer = false

    val size: Float get() = role.size

    fun distTo(o: Unit): Float {
        val dx = x - o.x
        val dy = y - o.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}

class Effect(var x: Float, var y: Float, var life: Int, var maxLife: Int, var color: Int, var text: String = "")

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val W = 1600f
    private val H = 900f

    private val units = mutableListOf<Unit>()
    private val effects = mutableListOf<Effect>()
    private var gameStarted = false
    private var gameEnded = false
    private var winner = ""

    private var joystickActive = false
    private var joyCX = 0f
    private var joyCY = 0f
    private var joyX = 0f
    private var joyY = 0f

    private var btnAttackX = 0f
    private var btnAttackY = 0f
    private var btnAttackR = 0f
    private var btnS1X = 0f
    private var btnS1Y = 0f
    private var btnS1R = 0f
    private var btnS2X = 0f
    private var btnS2Y = 0f
    private var btnS2R = 0f
    private var playerUnit: Unit? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        thread = Thread(this).also { it.start() }
        setup()
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
        joyCX = 220f
        joyCY = H - 220f
        btnAttackX = W - 180f
        btnAttackY = H - 180f
        btnAttackR = 110f
        btnS1X = W - 380f
        btnS1Y = H - 130f
        btnS1R = 80f
        btnS2X = W - 130f
        btnS2Y = H - 380f
        btnS2R = 80f
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        thread?.join()
    }

    private fun setup() {
        units.clear()
        effects.clear()
        gameEnded = false
        winner = ""

        val my = Unit(ROLES[0], 1, 300f, H / 2f)
        my.isPlayer = true
        units.add(my)
        playerUnit = my

        val e1 = Unit(ROLES[1], 2, W - 300f, 200f)
        val e2 = Unit(ROLES[2], 2, W - 300f, H / 2f)
        val e3 = Unit(ROLES[3], 2, W - 300f, H - 200f)
        units.add(e1)
        units.add(e2)
        units.add(e3)

        gameStarted = true
    }

    override fun run() {
        var lastTime = System.currentTimeMillis()
        while (running) {
            val now = System.currentTimeMillis()
            val dt = ((now - lastTime) / 16.666f).coerceAtMost(3f)
            lastTime = now
            update(dt)
            val c = holder.lockCanvas() ?: continue
            try {
                synchronized(holder) { draw(c) }
            } finally {
                holder.unlockCanvasAndPost(c)
            }
            Thread.sleep(16)
        }
    }

    private fun update(dt: Float) {
        if (!gameStarted || gameEnded) {
            effects.forEach { it.life-- }
            effects.removeAll { it.life <= 0 }
            return
        }

        for (u in units) {
            if (u.hp <= 0) {
                u.dead = true
                continue
            }
            if (u.cd > 0) u.cd--
            updatePassive(u)
            if (u.isPlayer) {
                val mag = Math.sqrt((joyX * joyX + joyY * joyY).toDouble()).toFloat()
                if (joystickActive && mag > 0.15f) {
                    val nx = joyX / mag
                    val ny = joyY / mag
                    val sp = u.role.speed * 1.3f * dt
                    u.x += nx * sp * 4f
                    u.y += ny * sp * 4f
                }
            } else {
                val target = findNearestEnemy(u)
                if (target != null) {
                    val dx = target.x - u.x
                    val dy = target.y - u.y
                    val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (d > u.size * 0.7f) {
                        val sp = u.role.speed * dt
                        u.x += dx / d * sp
                        u.y += dy / d * sp
                    }
                    if (d < 80f + u.size / 2 && u.cd <= 0) {
                        dealDamage(target, 15f, u, false)
                        effects.add(Effect(target.x, target.y, 12, 12, 0xFFFF6666.toInt()))
                        u.cd = 60
                    }
                }
            }
            u.x = u.x.coerceIn(u.size / 2, W - u.size / 2)
            u.y = u.y.coerceIn(u.size / 2, H - u.size / 2)
        }

        playerUnit?.let { pu ->
            if (pu.hp > 0) playerAutoAttack(pu)
        }

        val alive1 = units.count { it.team == 1 && it.hp > 0 }
        val alive2 = units.count { it.team == 2 && it.hp > 0 }
        if (alive1 == 0 || alive2 == 0) {
            gameEnded = true
            winner = if (alive1 > 0) "红方胜利！" else "蓝方胜利！"
        }

        effects.forEach { it.life-- }
        effects.removeAll { it.life <= 0 }
    }

    private fun findNearestEnemy(u: Unit): Unit? {
        var best: Unit? = null
        var bd = Float.MAX_VALUE
        for (o in units) {
            if (o.team != u.team && o.hp > 0) {
                val d = u.distTo(o)
                if (d < bd) {
                    bd = d
                    best = o
                }
            }
        }
        return best
    }

    private fun playerAutoAttack(u: Unit) {
        val target = findNearestEnemy(u) ?: return
        val d = u.distTo(target)
        if (d < 70f + u.size / 2 && u.cd <= 0) {
            dealDamage(target, 12f, u, false)
            effects.add(Effect(target.x, target.y, 12, 12, 0xFFFF6666.toInt()))
            u.cd = 45
        }
    }

    private fun updatePassive(u: Unit) {
        when (u.role.key) {
            "jiaotou" -> {
                if (u.reflectCooldown > 0) u.reflectCooldown--
                if (!u.lowHpBerserk) {
                    u.reduceCooldown++
                    if (u.reduceCooldown >= 240) {
                        u.reduceActive = true
                        u.reduceTimer = 0
                        u.reduceCooldown = 0
                        u.reflectActive = true
                        u.reflectTimer = 0
                        u.reflectCooldown = 240
                        effects.add(Effect(u.x, u.y - 60, 25, 25, 0xFFFF8844.toInt(), "减伤反伤"))
                    }
                    if (u.reduceActive) {
                        u.reduceTimer++
                        if (u.reflectActive) {
                            u.reflectTimer++
                            if (u.reflectTimer >= 60) u.reflectActive = false
                        }
                        if (u.reduceTimer >= 90) {
                            u.reduceActive = false
                            u.reflectActive = false
                        }
                    }
                }
                if (u.hp <= 90f && !u.lowHpBerserk) {
                    u.lowHpBerserk = true
                    u.reduceActive = false
                    u.reflectActive = false
                    effects.add(Effect(u.x, u.y - 80, 35, 35, 0xFFFF2200.toInt(), "残血狂暴"))
                }
            }
            "galo" -> {
                if (u.hp <= 100f && u.hp > 0 && !u.shieldTriggered) {
                    u.shieldTriggered = true
                    u.shieldActive = true
                    u.shieldDuration = 0
                    u.shield = 0f
                    effects.add(Effect(u.x, u.y - 60, 30, 30, 0xFF66DDFF.toInt(), "护盾开启"))
                }
                if (u.shieldActive) {
                    u.shieldDuration++
                    if (u.shieldDuration % 30 == 0 && u.shieldDuration <= 150) u.shield += 50f
                    if (u.shieldDuration >= 150) u.shieldActive = false
                }
            }
            "laobai" -> {
                u.invincibleCooldown++
                if (u.invincibleCooldown >= 150 && !u.invincible) {
                    u.invincible = true
                    u.invincibleTimer = 0
                    u.invincibleCooldown = 0
                    effects.add(Effect(u.x, u.y - 50, 20, 20, 0xFF44DDFF.toInt(), "隐身"))
                }
                if (u.invincible) {
                    u.invincibleTimer++
                    if (u.invincibleTimer >= 30) u.invincible = false
                }
                u.dashTimer++
                if (u.dashTimer >= 60) {
                    val enemy = findNearestEnemy(u)
                    if (enemy != null) {
                        val dx = enemy.x - u.x
                        val dy = enemy.y - u.y
                        val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (d < 200f && d > 1f) {
                            val len = minOf(80f, d)
                            u.x += dx / d * len
                            u.y += dy / d * len
                            if (d < 80f) {
                                dealDamage(enemy, 30f, u, false)
                                effects.add(Effect(enemy.x, enemy.y, 12, 12, 0xFF44DDFF.toInt()))
                            }
                        }
                    }
                    u.dashTimer = 0
                }
            }
        }
    }

    private fun dealDamage(target: Unit, dmg: Float, source: Unit?, isTrue: Boolean) {
        if (target.hp <= 0) return

        if (source?.role?.key == "death" && target.hp <= target.maxHp * 0.08f) {
            target.hp = 0f
            target.dead = true
            effects.add(Effect(target.x, target.y, 30, 30, 0xFFFF0000.toInt(), "斩杀"))
            return
        }

        if (target.role.key == "jiaotou" && target.reflectActive && source != null && source.hp > 0 && source.team != target.team) {
            dealDamage(source, dmg, target, true)
            effects.add(Effect(source.x, source.y - 30, 15, 15, 0xFFFFAA00.toInt(), "反伤"))
        }

        var remaining = dmg
        if (target.role.key == "galo" && target.shield > 0f && !isTrue) {
            val absorb = minOf(target.shield, remaining)
            target.shield -= absorb
            remaining -= absorb
        }

        if (target.role.key == "jiaotou" && !isTrue) {
            val red = when {
                target.lowHpBerserk -> 0.95f
                target.reduceActive -> 0.75f
                else -> 0f
            }
            if (red > 0f) {
                remaining *= (1f - red)
                effects.add(Effect(target.x, target.y - 30, 10, 10, 0xFFFF8844.toInt()))
            }
        }

        if (target.role.key == "laobai" && target.invincible && !isTrue) {
            effects.add(Effect(target.x, target.y - 40, 10, 10, 0xFF44DDFF.toInt(), "闪避"))
            return
        }

        target.hp -= remaining
        if (target.hp < 0) target.hp = 0f
        effects.add(Effect(target.x, target.y, 12, 12, 0xFFFF6666.toInt()))
        if (target.hp <= 0) {
            target.dead = true
            effects.add(Effect(target.x, target.y, 30, 30, 0xFFFF4444.toInt(), "KILL"))
        }
    }

    private fun draw(c: Canvas) {
        c.drawColor(0xFF0B1220.toInt())

        paint.color = 0xFF1F334A.toInt()
        paint.strokeWidth = 1f
        var gx = 0f
        while (gx < W) {
            c.drawLine(gx, 0f, gx, H, paint)
            gx += 60f
        }
        var gy = 0f
        while (gy < H) {
            c.drawLine(0f, gy, W, gy, paint)
            gy += 60f
        }

        for (u in units) {
            paint.alpha = if (u.hp <= 0 && u.dead) 90 else 255
            val half = u.size / 2f

            paint.color = if (u.team == 1) 0x44FF4444 else 0x444488FF
            c.drawCircle(u.x, u.y, half * 2f, paint)

            paint.color = u.role.color
            c.drawCircle(u.x, u.y, half, paint)

            paint.color = 0x55000000
            c.drawCircle(u.x, u.y, half * 0.85f, paint)

            textPaint.color = 0xFFFFFFFF.toInt()
            textPaint.textSize = half * 0.9f
            c.drawText(u.role.name.substring(0, 1), u.x, u.y + half * 0.35f, textPaint)

            if (u.isPlayer) {
                paint.style = Paint.Style.STROKE
                paint.color = 0xFFFFDD44.toInt()
                paint.strokeWidth = 5f
                c.drawCircle(u.x, u.y, half + 20f, paint)
                paint.style = Paint.Style.FILL
                textPaint.color = 0xFFFFDD44.toInt()
                textPaint.textSize = 24f
                c.drawText("你", u.x, u.y - half - 30f, textPaint)
            }

            val barW = u.size + 40f
            val barY = u.y - half - 30f
            paint.color = 0xDD000000.toInt()
            c.drawRect(u.x - barW / 2, barY, u.x + barW / 2, barY + 14f, paint)
            paint.color = if (u.hp / u.maxHp > 0.5f) 0xFF4CD964.toInt() else 0xFFFF6B6B.toInt()
            c.drawRect(u.x - barW / 2, barY, u.x - barW / 2 + barW * (u.hp / u.maxHp), barY + 14f, paint)

            textPaint.color = 0xFFFFFFFF.toInt()
            textPaint.textSize = 22f
            c.drawText("${u.role.name} ${u.hp.toInt()}/${u.maxHp.toInt()}", u.x, barY - 8f, textPaint)

            var tagY = barY - 40f
            fun drawTag(text: String, color: Int) {
                textPaint.color = color
                textPaint.textSize = 20f
                c.drawText(text, u.x, tagY, textPaint)
                tagY -= 24f
            }
            if (u.role.key == "jiaotou") {
                if (u.lowHpBerserk) drawTag("狂暴 95%免伤", 0xFFFF2200.toInt())
                else if (u.reflectActive) drawTag("反伤中", 0xFFFFAA00.toInt())
                else if (u.reduceActive) drawTag("减伤75%", 0xFFFF8844.toInt())
            }
            if (u.role.key == "galo") {
                if (u.shield > 0f) drawTag("护盾 ${u.shield.toInt()}", 0xFF66DDFF.toInt())
            }
            if (u.role.key == "laobai" && u.invincible) {
                drawTag("隐身中", 0xFF44DDFF.toInt())
            }
            if (u.role.key == "death") {
                drawTag("8%斩杀", 0xFFFF6666.toInt())
            }

            if (u.invincible || u.reflectActive) {
                paint.style = Paint.Style.STROKE
                paint.color = if (u.invincible) 0xFF44DDFF.toInt() else 0xFFFFAA00.toInt()
                paint.strokeWidth = 6f
                c.drawCircle(u.x, u.y, half + 25f, paint)
                paint.style = Paint.Style.FILL
            }

            paint.alpha = 255
        }

        for (e in effects) {
            val alpha = (e.life * 255 / e.maxLife).coerceIn(0, 255)
            paint.alpha = alpha
            paint.color = e.color
            c.drawCircle(e.x, e.y, 30f, paint)
            if (e.text.isNotEmpty()) {
                textPaint.alpha = alpha
                textPaint.color = e.color
                textPaint.textSize = 26f
                c.drawText(e.text, e.x, e.y - 40f, textPaint)
                textPaint.alpha = 255
            }
            paint.alpha = 255
        }

        if (joystickActive) {
            paint.color = 0x33FFFFFF
            c.drawCircle(joyCX, joyCY, 180f, paint)
            paint.color = 0x88FFFFFF.toInt()
            c.drawCircle(joyCX + joyX * 100f, joyCY + joyY * 100f, 70f, paint)
        } else {
            paint.color = 0x22FFFFFF
            c.drawCircle(joyCX, joyCY, 180f, paint)
            paint.color = 0x55FFFFFF
            c.drawCircle(joyCX, joyCY, 70f, paint)
        }

        drawButton(c, btnS1X, btnS1Y, btnS1R, "1", "技1", 0xAA4A7ACC.toInt())
        drawButton(c, btnS2X, btnS2Y, btnS2R, "2", "技2", 0xAA7A4ACC.toInt())
        drawButton(c, btnAttackX, btnAttackY, btnAttackR, "A", "普攻", 0xCCCC3333.toInt())

        if (gameEnded) {
            paint.color = 0xCC000000.toInt()
            c.drawRect(0f, H / 2 - 200f, W, H / 2 + 200f, paint)
            textPaint.color = 0xFFFFF4C2.toInt()
            textPaint.textSize = 100f
            c.drawText(winner, W / 2, H / 2 + 20f, textPaint)
            textPaint.color = 0xFF8899BB.toInt()
            textPaint.textSize = 36f
            c.drawText("点击屏幕重新开始", W / 2, H / 2 + 100f, textPaint)
        }
    }

    private fun drawButton(c: Canvas, x: Float, y: Float, r: Float, icon: String, label: String, color: Int) {
        paint.color = color
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.color = 0x88FFFFFF.toInt()
        paint.strokeWidth = 4f
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = r * 0.9f
        c.drawText(icon, x, y + r * 0.15f, textPaint)
        textPaint.textSize = r * 0.35f
        c.drawText(label, x, y + r * 0.75f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val px = event.getX(idx)
                val py = event.getY(idx)

                if (Math.sqrt(((px - joyCX) * (px - joyCX) + (py - joyCY) * (py - joyCY)).toDouble()) < 220) {
                    joystickActive = true
                    joyX = (px - joyCX) / 180f
                    joyY = (py - joyCY) / 180f
                    return true
                }

                if (Math.sqrt(((px - btnAttackX) * (px - btnAttackX) + (py - btnAttackY) * (py - btnAttackY)).toDouble()) < btnAttackR + 30) {
                    triggerAttack()
                    return true
                }
                if (Math.sqrt(((px - btnS1X) * (px - btnS1X) + (py - btnS1Y) * (py - btnS1Y)).toDouble()) < btnS1R + 30) {
                    triggerSkill1()
                    return true
                }
                if (Math.sqrt(((px - btnS2X) * (px - btnS2X) + (py - btnS2Y) * (py - btnS2Y)).toDouble()) < btnS2R + 30) {
                    triggerSkill2()
                    return true
                }

                if (gameEnded) {
                    setup()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (joystickActive) {
                    joyX = (event.x - joyCX) / 180f
                    joyY = (event.y - joyCY) / 180f
                    val m = Math.sqrt((joyX * joyX + joyY * joyY).toDouble()).toFloat()
                    if (m > 1f) {
                        joyX /= m
                        joyY /= m
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                joystickActive = false
                joyX = 0f
                joyY = 0f
            }
        }
        return true
    }

    private fun triggerAttack() {
        val u = playerUnit ?: return
        if (u.hp <= 0) return
        val target = findNearestEnemy(u) ?: return
        val d = u.distTo(target)
        if (d < 100f) {
            dealDamage(target, 15f, u, false)
            effects.add(Effect(target.x, target.y, 12, 12, 0xFFFF4444.toInt()))
        }
    }

    private fun triggerSkill1() {
        val u = playerUnit ?: return
        if (u.hp <= 0) return
        val target = findNearestEnemy(u) ?: return
        when (u.role.key) {
            "death" -> {
                dealDamage(target, 30f, u, false)
                effects.add(Effect(target.x, target.y, 20, 20, 0xFFFF4444.toInt(), "死亡凝视"))
            }
            "galo" -> {
                val dx = target.x - u.x
                val dy = target.y - u.y
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (d > 1f) {
                    u.x += dx / d * 120f
                    u.y += dy / d * 120f
                    dealDamage(target, 40f, u, false)
                }
                effects.add(Effect(u.x, u.y, 20, 20, 0xFFFFAA33.toInt(), "雷霆冲刺"))
            }
            "laobai" -> {
                val dx = target.x - u.x
                val dy = target.y - u.y
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (d > 1f) {
                    u.x += dx / d * 100f
                    u.y += dy / d * 100f
                    if (d < 100f) dealDamage(target, 25f, u, false)
                }
                effects.add(Effect(u.x, u.y, 20, 20, 0xFF44DDFF.toInt(), "隐者突袭"))
            }
            "jiaotou" -> {
                dealDamage(target, 20f, u, false)
                target.cd = 120
                effects.add(Effect(target.x, target.y, 25, 25, 0xFFFF8844.toInt(), "木桩困敌"))
            }
        }
    }

    private fun triggerSkill2() {
        val u = playerUnit ?: return
        if (u.hp <= 0) return
        for (o in units) {
            if (o.team != u.team && o.hp > 0 && u.distTo(o) < 180f) {
                dealDamage(o, 25f, u, false)
                effects.add(Effect(o.x, o.y, 15, 15, 0xFFFF8844.toInt()))
            }
        }
        effects.add(Effect(u.x, u.y, 20, 20, 0xFFFF8844.toInt(), "范围爆发"))
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(GameView(this))
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }
}
