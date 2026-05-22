import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.prefs.Preferences;

public class Just_Dodge_it extends JPanel implements ActionListener, KeyListener {

    private final int WIDTH = 600;
    private final int HEIGHT = 850;
    private final int CAR_W = 75, CAR_H = 140;
    private final int[] LANES = {115, 262, 410};

    private enum State { MENU, PLAYING, GAMEOVER }
    private State gameState = State.MENU;
    private String gameOverReason = "WRECKED";
    private Timer timer;
    private Random rnd = new Random();

    private Preferences prefs = Preferences.userNodeForPackage(Just_Dodge_it.class);
    private int highScore = 0;
    private boolean newHighScore = false;

    private BufferedImage playerSprite, enemySprite, repairSprite, fuelSprite;

    private float pX = 262, pVelX = 0;
    private float worldSpeed = 0, targetSpeed = 10;
    private float fuel = 100, health = 100, nitro = 100;
    private int score = 0;
    private float roadOffset = 0;
    private boolean left, right, nitroActive;

    private int spawnCooldown = 0;

    private ArrayList<GameObject> entities = new ArrayList<>();
    private ArrayList<Particle> particles = new ArrayList<>();

    public Just_Dodge_it() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(new Color(25, 25, 25));
        setFocusable(true);
        addKeyListener(this);
        highScore = prefs.getInt("highscore", 0);
        generateSprites();
        timer = new Timer(16, this);
        timer.start();
    }

    private void generateSprites() {
        playerSprite = createCarImage(new Color(0, 200, 255), true);
        enemySprite  = createCarImage(new Color(255, 50, 50), false);
        repairSprite = createIcon(new Color(50, 255, 50), "+");
        fuelSprite   = createIcon(new Color(255, 160, 0), "F");
    }

    private BufferedImage createCarImage(Color bodyColor, boolean isPlayer) {
        BufferedImage img = new BufferedImage(CAR_W, CAR_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(bodyColor);
        g.fillRoundRect(5, 5, CAR_W - 10, CAR_H - 10, 25, 25);
        g.setColor(new Color(20, 20, 20, 200));
        if (isPlayer) g.fillRoundRect(15, 35, CAR_W - 30, 45, 10, 10);
        else          g.fillRoundRect(15, CAR_H - 80, CAR_W - 30, 45, 10, 10);
        g.setColor(Color.WHITE);
        if (isPlayer) {
            g.fillOval(10, 10, 15, 8); g.fillOval(CAR_W - 25, 10, 15, 8);
        } else {
            g.fillOval(10, CAR_H - 18, 15, 8); g.fillOval(CAR_W - 25, CAR_H - 18, 15, 8);
        }
        g.dispose();
        return img;
    }

    private BufferedImage createIcon(Color c, String symbol) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillOval(2, 2, 36, 36);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 25));
        g.drawString(symbol, 12, 29);
        g.dispose();
        return img;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (gameState == State.PLAYING) updateLogic();
        repaint();
    }

    private void updateLogic() {
        // Steering acceleration scales with world speed so the player can always cross lanes in time
        float steerAccel = 1.8f + (worldSpeed - 12f) * 0.22f;
        if (left)  pVelX -= steerAccel;
        if (right) pVelX += steerAccel;
        pVelX *= 0.82f;
        pX += pVelX;
        if (pX < 100)             { pX = 100;             pVelX *= -0.5; health -= 0.2; }
        if (pX > WIDTH-100-CAR_W) { pX = WIDTH-100-CAR_W; pVelX *= -0.5; health -= 0.2; }

        if (nitroActive && nitro > 0) {
            targetSpeed = 25; nitro -= 0.6f;
            spawnParticles(pX + CAR_W / 2, HEIGHT - 100, Color.ORANGE);
        } else {
            float baseSpeed = 17 + Math.min(score / 400f, 8f); // caps at 20 when score = 8000
            targetSpeed = baseSpeed;
            if (nitro < 100) nitro += 0.1f;
        }
        if (health < 100) health = Math.min(100, health + 0.03f);
        worldSpeed += (targetSpeed - worldSpeed) * 0.08f;
        roadOffset = (roadOffset + worldSpeed) % 100;

        Iterator<GameObject> it = entities.iterator();
        while (it.hasNext()) {
            GameObject obj = it.next();
            obj.y += worldSpeed;
            Rectangle playerHitbox = new Rectangle((int)pX + 5, HEIGHT - 180, CAR_W - 10, CAR_H - 10);
            if (playerHitbox.intersects(obj.getBounds())) {
                handleCollision(obj); it.remove(); continue;
            }
            if (obj.y > HEIGHT) it.remove();
        }

        for (int i = particles.size() - 1; i >= 0; i--) {
            particles.get(i).update();
            if (particles.get(i).life <= 0) particles.remove(i);
        }

        if (spawnCooldown > 0) {
            spawnCooldown--;
        } else {
            // Spawn chance scales from 14% → 28% as score grows
            int spawnChance = Math.min(28, 14 + (score / 400));
            if (rnd.nextInt(100) < spawnChance) spawnWave();
        }

        score += (worldSpeed / 10);
        fuel  -= 0.08f;

        if (health <= 0) { gameOverReason = "WRECKED";     gameState = State.GAMEOVER; saveHighScore(); }
        if (fuel   <= 0) { gameOverReason = "OUT OF FUEL"; gameState = State.GAMEOVER; saveHighScore(); }
    }

    private void spawnWave() {
        // 1 car early, up to 2 after score 1500 (never all 3 lanes)
        int maxCars = (score > 1500) ? 2 : 1;
        int carsToSpawn = 1 + (maxCars > 1 && rnd.nextBoolean() ? 1 : 0);

        // Shuffle lanes
        int[] order = {0, 1, 2};
        for (int i = 2; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int tmp = order[i]; order[i] = order[j]; order[j] = tmp;
        }

        // Abort if still crowded near top
        for (GameObject obj : entities) {
            if (obj.type == EntityType.ENEMY && obj.y < 160) return;
        }

        for (int i = 0; i < carsToSpawn; i++) {
            entities.add(new GameObject(LANES[order[i]], -CAR_H - (i * 10), CAR_W, CAR_H, EntityType.ENEMY));
        }

        // 20% chance: powerup in a free lane
        if (rnd.nextInt(100) < 20) {
            int freeLane = LANES[order[carsToSpawn]];
            EntityType type = rnd.nextBoolean() ? EntityType.REPAIR : EntityType.FUEL;
            entities.add(new GameObject(freeLane + 15, -CAR_H - 50, 40, 40, type));
        }

        // Cooldown: starts at 50 frames, shrinks to 18 at score 8000
        spawnCooldown = Math.max(18, 50 - (score / 250));
    }

    private void handleCollision(GameObject obj) {
        switch (obj.type) {
            case ENEMY  -> { health -= 25; spawnParticles(obj.x + 30, obj.y + 50, Color.RED); }
            case REPAIR -> health = Math.min(100, health + 20);
            case FUEL   -> fuel   = Math.min(100, fuel   + 25);
        }
    }

    private void spawnParticles(float x, float y, Color c) {
        for (int i = 0; i < 5; i++) particles.add(new Particle(x, y, c));
    }

    private void saveHighScore() {
        if (score > highScore) {
            highScore = score; newHighScore = true;
            prefs.putInt("highscore", highScore);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawRoad(g2d);

        for (GameObject obj : entities) {
            BufferedImage img = switch (obj.type) {
                case ENEMY  -> enemySprite;
                case REPAIR -> repairSprite;
                case FUEL   -> fuelSprite;
            };
            g2d.drawImage(img, obj.x, obj.y, obj.w, obj.h, null);
        }

        int shake = (nitroActive && gameState == State.PLAYING) ? rnd.nextInt(5) - 2 : 0;
        g2d.drawImage(playerSprite, (int)pX + shake, HEIGHT - 200 + shake, null);

        for (Particle p : particles) {
            g2d.setColor(new Color(p.c.getRed(), p.c.getGreen(), p.c.getBlue(), (int)(p.life * 255)));
            g2d.fillOval((int)p.x, (int)p.y, 6, 6);
        }

        drawUI(g2d);
        Toolkit.getDefaultToolkit().sync();
    }

    private void drawRoad(Graphics2D g2d) {
        g2d.setColor(new Color(40, 42, 54));
        g2d.fillRect(100, 0, WIDTH - 200, HEIGHT);
        g2d.setColor(new Color(255, 255, 255, 100));
        for (int i = -100; i < HEIGHT; i += 100) {
            int yPos = (int)(i + roadOffset);
            g2d.fillRect(230, yPos, 8, 50);
            g2d.fillRect(360, yPos, 8, 50);
        }
    }

    private void drawUI(Graphics2D g2d) {
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRoundRect(20, 20, 220, 160, 20, 20);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 18));
        g2d.drawString("SCORE: " + (int)score, 40, 50);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g2d.drawString("BEST:  " + highScore, 40, 68);
        drawBar(g2d, 40, 90,  "HULL",  health / 100f, Color.RED);
        drawBar(g2d, 40, 115, "NITRO", nitro  / 100f, Color.CYAN);
        drawBar(g2d, 40, 140, "FUEL",  fuel   / 100f, Color.ORANGE);

        if (gameState != State.PLAYING) {
            g2d.setColor(new Color(0, 0, 0, 210));
            g2d.fillRect(0, 0, WIDTH, HEIGHT);
            g2d.setColor(Color.CYAN);
            g2d.setFont(new Font("SansSerif", Font.BOLD, gameState == State.MENU ? 50 : 40));
            String t = (gameState == State.MENU) ? "JUST DODGE IT" : gameOverReason;
            g2d.drawString(t, WIDTH / 2 - g2d.getFontMetrics().stringWidth(t) / 2, HEIGHT / 2 - 20);
            if (gameState == State.GAMEOVER) {
                g2d.setFont(new Font("SansSerif", Font.PLAIN, 18));
                g2d.setColor(Color.WHITE);
                g2d.drawString("SCORE: " + (int)score + "   BEST: " + highScore, WIDTH / 2 - 110, HEIGHT / 2 + 20);
                if (newHighScore) {
                    g2d.setColor(Color.YELLOW);
                    g2d.setFont(new Font("SansSerif", Font.BOLD, 18));
                    g2d.drawString("NEW HIGHSCORE!", WIDTH / 2 - 80, HEIGHT / 2 + 45);
                }
            }
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
            g2d.setColor(Color.WHITE);
            g2d.drawString("PRESS ENTER TO START", WIDTH / 2 - 110, HEIGHT / 2 + 75);
        }
    }

    private void drawBar(Graphics2D g2d, int x, int y, String label, float pct, Color c) {
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));
        g2d.setColor(Color.WHITE);
        g2d.drawString(label, x, y);
        g2d.setColor(Color.BLACK);
        g2d.fillRect(x + 45, y - 8, 120, 10);
        g2d.setColor(c);
        g2d.fillRect(x + 45, y - 8, (int)(120 * Math.max(0, pct)), 10);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_LEFT)  left = true;
        if (k == KeyEvent.VK_RIGHT) right = true;
        if (k == KeyEvent.VK_SPACE) nitroActive = true;
        if (k == KeyEvent.VK_ENTER && gameState != State.PLAYING) reset();
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_LEFT)  left = false;
        if (k == KeyEvent.VK_RIGHT) right = false;
        if (k == KeyEvent.VK_SPACE) nitroActive = false;
    }

    private void reset() {
        score = 0; health = 100; nitro = 100; fuel = 100;
        entities.clear(); particles.clear();
        pX = 262; pVelX = 0; spawnCooldown = 0;
        newHighScore = false; gameOverReason = "WRECKED";
        gameState = State.PLAYING;
    }

    @Override public void keyTyped(KeyEvent e) {}

    public static void main(String[] args) {
        JFrame f = new JFrame("Just Dodge It");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setResizable(false);
        f.add(new Just_Dodge_it());
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    enum EntityType { ENEMY, REPAIR, FUEL }
    class GameObject {
        int x, y, w, h; EntityType type;
        GameObject(int x, int y, int w, int h, EntityType t) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.type = t;
        }
        Rectangle getBounds() { return new Rectangle(x, y, w, h); }
    }
    class Particle {
        float x, y, vx, vy, life = 1.0f; Color c;
        Particle(float x, float y, Color c) {
            this.x = x; this.y = y; this.c = c;
            this.vx = (float)(Math.random() * 4 - 2);
            this.vy = (float)(Math.random() * 4 - 2);
        }
        void update() { x += vx; y += vy; life -= 0.04f; }
    }
}
